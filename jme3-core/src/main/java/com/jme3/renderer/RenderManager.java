/*
 * Copyright (c) 2024 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.renderer;

import com.jme3.renderer.pipeline.ForwardPipeline;
import com.jme3.renderer.pipeline.DefaultPipelineContext;
import com.jme3.renderer.pipeline.RenderPipeline;
import com.jme3.renderer.pipeline.PipelineContext;
import com.jme3.light.DefaultLightFilter;
import com.jme3.light.LightFilter;
import com.jme3.light.LightList;
import com.jme3.material.MatParamOverride;
import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.material.RenderState;
import com.jme3.material.Technique;
import com.jme3.material.TechniqueDef;
import com.jme3.math.Matrix4f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.profile.AppStep;
import com.jme3.profile.VpStep;
import com.jme3.renderer.queue.GeometryList;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.Shader;
import com.jme3.shader.UniformBinding;
import com.jme3.shader.UniformBindingManager;
import com.jme3.shader.VarType;
import com.jme3.system.NullRenderer;
import com.jme3.system.Timer;
import com.jme3.texture.FrameBuffer;
import com.jme3.util.SafeArrayList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * A high-level rendering interface that is
 * above the Renderer implementation. RenderManager takes care
 * of rendering the scene graphs attached to each viewport and
 * handling SceneProcessors.
 *
 * @see SceneProcessor
 * @see ViewPort
 * @see Spatial
 */
public class RenderManager {

    private static final Logger logger = Logger.getLogger(RenderManager.class.getName());
    private final Renderer renderer;
    private final UniformBindingManager uniformBindingManager = new UniformBindingManager();
    private final ArrayList<ViewPort> preViewPorts = new ArrayList<>();
    private final ArrayList<ViewPort> viewPorts = new ArrayList<>();
    private final ArrayList<ViewPort> postViewPorts = new ArrayList<>();
    private final HashMap<Class, PipelineContext> contexts = new HashMap<>();
    private final LinkedList<PipelineContext> usedContexts = new LinkedList<>();
    private final LinkedList<RenderPipeline> usedPipelines = new LinkedList<>();
    private RenderPipeline defaultPipeline = new ForwardPipeline();
    private Camera prevCam = null;
    private Material forcedMaterial = null;
    private String forcedTechnique = null;
    private RenderState forcedRenderState = null;
    private final SafeArrayList<MatParamOverride> forcedOverrides
            = new SafeArrayList<>(MatParamOverride.class);
    private int viewX;
    private int viewY;
    private int viewWidth;
    private int viewHeight;
    private final Matrix4f orthoMatrix = new Matrix4f();
    private final LightList filteredLightList = new LightList(null);
    private boolean handleTranslucentBucket = true;
    private AppProfiler prof;
    private LightFilter lightFilter = new DefaultLightFilter();
    private TechniqueDef.LightMode preferredLightMode = TechniqueDef.LightMode.MultiPass;
    private int singlePassLightBatchSize = 1;
    private MatParamOverride boundDrawBufferId=new MatParamOverride(VarType.Int, "BoundDrawBuffer", 0);
    private Predicate<Geometry> renderFilter;


    /**
     * Creates a high-level rendering interface over the
     * low-level rendering interface.
     *
     * @param renderer (alias created)
     */
    public RenderManager(Renderer renderer) {
        this.renderer = renderer;
        this.forcedOverrides.add(boundDrawBufferId);
        // register default pipeline context
        contexts.put(PipelineContext.class, new DefaultPipelineContext());
    }
    
    /**
     * Gets the default pipeline used when a ViewPort does not have a
     * pipeline already assigned to it.
     * 
     * @return 
     */
    public RenderPipeline getPipeline() {
        return defaultPipeline;
    }
    
    /**
     * Sets the default pipeline used when a ViewPort does not have a
     * pipeline already assigned to it.
     * <p>
     * default={@link ForwardPipeline}
     * 
     * @param pipeline default pipeline (not null)
     */
    public void setPipeline(RenderPipeline pipeline) {
        assert pipeline != null;
        this.defaultPipeline = pipeline;
    }
    
    /**
     * Gets the default pipeline context registered under
     * {@link PipelineContext#getClass()}.
     * 
     * @return 
     */
    public PipelineContext getDefaultContext() {
        return getContext(PipelineContext.class);
    }
    
    /**
     * Gets the pipeline context registered under the class.
     * 
     * @param <T>
     * @param type
     * @return registered context or null
     */
    public <T extends PipelineContext> T getContext(Class<T> type) {
        return (T)contexts.get(type);
    }
    
    /**
     * Gets the pipeline context registered under the class or creates
     * and registers a new context from the supplier.
     * 
     * @param <T>
     * @param type
     * @param supplier interface for creating a new context if necessary
     * @return registered or newly created context
     */
    public <T extends PipelineContext> T getOrCreateContext(Class<T> type, Supplier<T> supplier) {
        T c = getContext(type);
        if (c == null) {
            c = supplier.get();
            registerContext(type, c);
        }
        return c;
    }
    
    /**
     * Gets the pipeline context registered under the class or creates
     * and registers a new context from the function.
     * 
     * @param <T>
     * @param type
     * @param function interface for creating a new context if necessary
     * @return registered or newly created context
     */
    public <T extends PipelineContext> T getOrCreateContext(Class<T> type, Function<RenderManager, T> function) {
        T c = getContext(type);
        if (c == null) {
            c = function.apply(this);
            registerContext(type, c);
        }
        return c;
    }
    
    /**
     * Registers the pipeline context under the class.
     * <p>
     * If another context is already registered under the class, that
     * context will be replaced by the given context.
     * 
     * @param <T>
     * @param type class type to register the context under (not null)
     * @param context context to register (not null)
     */
    public <T extends PipelineContext> void registerContext(Class<T> type, T context) {
        assert type != null;
        if (context == null) {
            throw new NullPointerException("Context to register cannot be null.");
        }
        contexts.put(type, context);
    }
    
    /**
     * Gets the application profiler.
     * 
     * @return 
     */
    public AppProfiler getProfiler() {
        return prof;
    }

    /**
     * Returns the pre ViewPort with the given name.
     *
     * @param viewName The name of the pre ViewPort to look up
     * @return The ViewPort, or null if not found.
     *
     * @see #createPreView(java.lang.String, com.jme3.renderer.Camera)
     */
    public ViewPort getPreView(String viewName) {
        for (int i = 0; i < preViewPorts.size(); i++) {
            if (preViewPorts.get(i).getName().equals(viewName)) {
                return preViewPorts.get(i);
            }
        }
        return null;
    }

    /**
     * Removes the pre ViewPort with the specified name.
     *
     * @param viewName The name of the pre ViewPort to remove
     * @return True if the ViewPort was removed successfully.
     *
     * @see #createPreView(java.lang.String, com.jme3.renderer.Camera)
     */
    public boolean removePreView(String viewName) {
        for (int i = 0; i < preViewPorts.size(); i++) {
            if (preViewPorts.get(i).getName().equals(viewName)) {
                preViewPorts.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the specified pre ViewPort.
     *
     * @param view The pre ViewPort to remove
     * @return True if the ViewPort was removed successfully.
     *
     * @see #createPreView(java.lang.String, com.jme3.renderer.Camera)
     */
    public boolean removePreView(ViewPort view) {
        return preViewPorts.remove(view);
    }

    /**
     * Returns the main ViewPort with the given name.
     *
     * @param viewName The name of the main ViewPort to look up
     * @return The ViewPort, or null if not found.
     *
     * @see #createMainView(java.lang.String, com.jme3.renderer.Camera)
     */
    public ViewPort getMainView(String viewName) {
        for (int i = 0; i < viewPorts.size(); i++) {
            if (viewPorts.get(i).getName().equals(viewName)) {
                return viewPorts.get(i);
            }
        }
        return null;
    }

    /**
     * Removes the main ViewPort with the specified name.
     *
     * @param viewName The main ViewPort name to remove
     * @return True if the ViewPort was removed successfully.
     *
     * @see #createMainView(java.lang.String, com.jme3.renderer.Camera)
     */
    public boolean removeMainView(String viewName) {
        for (int i = 0; i < viewPorts.size(); i++) {
            if (viewPorts.get(i).getName().equals(viewName)) {
                viewPorts.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the specified main ViewPort.
     *
     * @param view The main ViewPort to remove
     * @return True if the ViewPort was removed successfully.
     *
     * @see #createMainView(java.lang.String, com.jme3.renderer.Camera)
     */
    public boolean removeMainView(ViewPort view) {
        return viewPorts.remove(view);
    }

    /**
     * Returns the post ViewPort with the given name.
     *
     * @param viewName The name of the post ViewPort to look up
     * @return The ViewPort, or null if not found.
     *
     * @see #createPostView(java.lang.String, com.jme3.renderer.Camera)
     */
    public ViewPort getPostView(String viewName) {
        for (int i = 0; i < postViewPorts.size(); i++) {
            if (postViewPorts.get(i).getName().equals(viewName)) {
                return postViewPorts.get(i);
            }
        }
        return null;
    }

    /**
     * Removes the post ViewPort with the specified name.
     *
     * @param viewName The post ViewPort name to remove
     * @return True if the ViewPort was removed successfully.
     *
     * @see #createPostView(java.lang.String, com.jme3.renderer.Camera)
     */
    public boolean removePostView(String viewName) {
        for (int i = 0; i < postViewPorts.size(); i++) {
            if (postViewPorts.get(i).getName().equals(viewName)) {
                postViewPorts.remove(i);

                return true;
            }
        }
        return false;
    }

    /**
     * Removes the specified post ViewPort.
     *
     * @param view The post ViewPort to remove
     * @return True if the ViewPort was removed successfully.
     *
     * @see #createPostView(java.lang.String, com.jme3.renderer.Camera)
     */
    public boolean removePostView(ViewPort view) {
        return postViewPorts.remove(view);
    }

    /**
     * Returns a read-only list of all pre ViewPorts.
     *
     * @return a read-only list of all pre ViewPorts
     * @see #createPreView(java.lang.String, com.jme3.renderer.Camera)
     */
    public List<ViewPort> getPreViews() {
        return Collections.unmodifiableList(preViewPorts);
    }

    /**
     * Returns a read-only list of all main ViewPorts.
     *
     * @return a read-only list of all main ViewPorts
     * @see #createMainView(java.lang.String, com.jme3.renderer.Camera)
     */
    public List<ViewPort> getMainViews() {
        return Collections.unmodifiableList(viewPorts);
    }

    /**
     * Returns a read-only list of all post ViewPorts.
     *
     * @return a read-only list of all post ViewPorts
     * @see #createPostView(java.lang.String, com.jme3.renderer.Camera)
     */
    public List<ViewPort> getPostViews() {
        return Collections.unmodifiableList(postViewPorts);
    }

    /**
     * Creates a new pre ViewPort, to display the given camera's content.
     *
     * <p>The view will be processed before the main and post viewports.
     *
     * @param viewName the desired viewport name
     * @param cam the Camera to use for rendering (alias created)
     * @return a new instance
     */
    public ViewPort createPreView(String viewName, Camera cam) {
        ViewPort vp = new ViewPort(viewName, cam);
        preViewPorts.add(vp);
        return vp;
    }

    /**
     * Creates a new main ViewPort, to display the given camera's content.
     *
     * <p>The view will be processed before the post viewports but after
     * the pre viewports.
     *
     * @param viewName the desired viewport name
     * @param cam the Camera to use for rendering (alias created)
     * @return a new instance
     */
    public ViewPort createMainView(String viewName, Camera cam) {
        ViewPort vp = new ViewPort(viewName, cam);
        viewPorts.add(vp);
        return vp;
    }

    /**
     * Creates a new post ViewPort, to display the given camera's content.
     *
     * <p>The view will be processed after the pre and main viewports.
     *
     * @param viewName the desired viewport name
     * @param cam the Camera to use for rendering (alias created)
     * @return a new instance
     */
    public ViewPort createPostView(String viewName, Camera cam) {
        ViewPort vp = new ViewPort(viewName, cam);
        postViewPorts.add(vp);
        return vp;
    }

    private void notifyReshape(ViewPort vp, int w, int h) {
        List<SceneProcessor> processors = vp.getProcessors();
        for (SceneProcessor proc : processors) {
            if (!proc.isInitialized()) {
                proc.initialize(this, vp);
            } else {
                proc.reshape(vp, w, h);
            }
        }
    }

    private void notifyRescale(ViewPort vp, float x, float y) {
        List<SceneProcessor> processors = vp.getProcessors();
        for (SceneProcessor proc : processors) {
            if (!proc.isInitialized()) {
                proc.initialize(this, vp);
            } else {
                proc.rescale(vp, x, y);
            }
        }
    }

    /**
     * Internal use only.
     * Updates the resolution of all on-screen cameras to match
     * the given width and height.
     *
     * @param w the new width (in pixels)
     * @param h the new height (in pixels)
     */
    public void notifyReshape(int w, int h) {
        for (ViewPort vp : preViewPorts) {
            if (vp.getOutputFrameBuffer() == null) {
                Camera cam = vp.getCamera();
                cam.resize(w, h, true);
            }
            notifyReshape(vp, w, h);
        }
        for (ViewPort vp : viewPorts) {
            if (vp.getOutputFrameBuffer() == null) {
                Camera cam = vp.getCamera();
                cam.resize(w, h, true);
            }
            notifyReshape(vp, w, h);
        }
        for (ViewPort vp : postViewPorts) {
            if (vp.getOutputFrameBuffer() == null) {
                Camera cam = vp.getCamera();
                cam.resize(w, h, true);
            }
            notifyReshape(vp, w, h);
        }
    }

    /**
     * Internal use only.
     * Updates the scale of all on-screen ViewPorts
     *
     * @param x the new horizontal scale
     * @param y the new vertical scale
     */
    public void notifyRescale(float x, float y) {
        for (ViewPort vp : preViewPorts) {
            notifyRescale(vp, x, y);
        }
        for (ViewPort vp : viewPorts) {      
            notifyRescale(vp, x, y);
        }
        for (ViewPort vp : postViewPorts) {
            notifyRescale(vp, x, y);
        }
    }

    /**
     * Sets the material to use to render all future objects.
     * This overrides the material set on the geometry and renders
     * with the provided material instead.
     * Use null to clear the material and return renderer to normal
     * functionality.
     *
     * @param mat The forced material to set, or null to return to normal
     */
    public void setForcedMaterial(Material mat) {
        forcedMaterial = mat;
    }
    
    /**
     * Gets the forced material.
     * 
     * @return 
     */
    public Material getForcedMaterial() {
        return forcedMaterial;
    }

    /**
     * Returns the forced render state previously set with
     * {@link #setForcedRenderState(com.jme3.material.RenderState) }.
     *
     * @return the forced render state
     */
    public RenderState getForcedRenderState() {
        return forcedRenderState;
    }

    /**
     * Sets the render state to use for all future objects.
     * This overrides the render state set on the material and instead
     * forces this render state to be applied for all future materials
     * rendered. Set to null to return to normal functionality.
     *
     * @param forcedRenderState The forced render state to set, or null
     *     to return to normal
     */
    public void setForcedRenderState(RenderState forcedRenderState) {
        this.forcedRenderState = forcedRenderState;
    }

    /**
     * Sets the timer that should be used to query the time based
     * {@link UniformBinding}s for material world parameters.
     *
     * @param timer The timer to query time world parameters
     */
    public void setTimer(Timer timer) {
        uniformBindingManager.setTimer(timer);
    }

    /**
     * Sets an AppProfiler hook that will be called back for
     * specific steps within a single update frame.  Value defaults
     * to null.
     *
     * @param prof the AppProfiler to use (alias created, default=null)
     */
    public void setAppProfiler(AppProfiler prof) {
        this.prof = prof;
    }

    /**
     * Returns the forced technique name set.
     *
     * @return the forced technique name set.
     *
     * @see #setForcedTechnique(java.lang.String)
     */
    public String getForcedTechnique() {
        return forcedTechnique;
    }

    /**
     * Sets the forced technique to use when rendering geometries.
     *
     * <p>If the specified technique name is available on the geometry's
     * material, then it is used, otherwise, the
     * {@link #setForcedMaterial(com.jme3.material.Material) forced material} is used.
     * If a forced material is not set and the forced technique name cannot
     * be found on the material, the geometry will <em>not</em> be rendered.
     *
     * @param forcedTechnique The forced technique name to use, set to null
     *     to return to normal functionality.
     *
     * @see #renderGeometry(com.jme3.scene.Geometry)
     */
    public void setForcedTechnique(String forcedTechnique) {
        this.forcedTechnique = forcedTechnique;
    }

    /**
     * Adds a forced material parameter to use when rendering geometries.
     *
     * <p>The provided parameter takes precedence over parameters set on the
     * material or any overrides that exist in the scene graph that have the
     * same name.
     *
     * @param override The override to add
     * @see MatParamOverride
     * @see #removeForcedMatParam(com.jme3.material.MatParamOverride)
     */
    public void addForcedMatParam(MatParamOverride override) {
        forcedOverrides.add(override);
    }

    /**
     * Removes a forced material parameter previously added.
     *
     * @param override The override to remove.
     * @see #addForcedMatParam(com.jme3.material.MatParamOverride)
     */
    public void removeForcedMatParam(MatParamOverride override) {
        forcedOverrides.remove(override);
    }

    /**
     * Gets the forced material parameters applied to rendered geometries.
     *
     * <p>Forced parameters can be added via
     * {@link #addForcedMatParam(com.jme3.material.MatParamOverride)} or removed
     * via {@link #removeForcedMatParam(com.jme3.material.MatParamOverride)}.
     *
     * @return The forced material parameters.
     */
    public SafeArrayList<MatParamOverride> getForcedMatParams() {
        return forcedOverrides;
    }

    /**
     * Enables or disables alpha-to-coverage.
     *
     * <p>When alpha to coverage is enabled and the renderer implementation
     * supports it, then alpha blending will be replaced with alpha dissolve
     * if multi-sampling is also set on the renderer.
     * This feature allows avoiding of alpha blending artifacts due to
     * lack of triangle-level back-to-front sorting.
     *
     * @param value True to enable alpha-to-coverage, false otherwise.
     */
    public void setAlphaToCoverage(boolean value) {
        renderer.setAlphaToCoverage(value);
    }

    /**
     * True if the translucent bucket should automatically be rendered
     * by the RenderManager.
     *
     * @return true if the translucent bucket is rendered
     *
     * @see #setHandleTranslucentBucket(boolean)
     */
    public boolean isHandleTranslucentBucket() {
        return handleTranslucentBucket;
    }

    /**
     * Enables or disables rendering of the
     * {@link Bucket#Translucent translucent bucket}
     * by the RenderManager. The default is enabled.
     *
     * @param handleTranslucentBucket true to render the translucent bucket
     */
    public void setHandleTranslucentBucket(boolean handleTranslucentBucket) {
        this.handleTranslucentBucket = handleTranslucentBucket;
    }

    /**
     * Internal use only. Sets the world matrix to use for future
     * rendering. This has no effect unless objects are rendered manually
     * using {@link Material#render(com.jme3.scene.Geometry, com.jme3.renderer.RenderManager) }.
     * Using {@link #renderGeometry(com.jme3.scene.Geometry) } will
     * override this value.
     *
     * @param mat The world matrix to set
     */
    public void setWorldMatrix(Matrix4f mat) {
        uniformBindingManager.setWorldMatrix(mat);
    }

    /**
     * Internal use only.
     * Updates the given list of uniforms with {@link UniformBinding uniform bindings}
     * based on the current world state.
     *
     * @param shader (not null)
     */
    public void updateUniformBindings(Shader shader) {
        uniformBindingManager.updateUniformBindings(shader);
    }

    /**
     * Renders the given geometry.
     *
     * <p>First the proper world matrix is set, if
     * the geometry's {@link Geometry#setIgnoreTransform(boolean) ignore transform}
     * feature is enabled, the identity world matrix is used, otherwise, the
     * geometry's {@link Geometry#getWorldMatrix() world transform matrix} is used.
     *
     * <p>Once the world matrix is applied, the proper material is chosen for rendering.
     * If a {@link #setForcedMaterial(com.jme3.material.Material) forced material} is
     * set on this RenderManager, then it is used for rendering the geometry,
     * otherwise, the {@link Geometry#getMaterial() geometry's material} is used.
     *
     * <p>If a {@link #setForcedTechnique(java.lang.String) forced technique} is
     * set on this RenderManager, then it is selected automatically
     * on the geometry's material and is used for rendering. Otherwise, one
     * of the {@link com.jme3.material.MaterialDef#getTechniqueDefsNames() default techniques} is
     * used.
     *
     * <p>If a {@link #setForcedRenderState(com.jme3.material.RenderState) forced
     * render state} is set on this RenderManager, then it is used
     * for rendering the material, and the material's own render state is ignored.
     * Otherwise, the material's render state is used as intended.
     *
     * @param geom The geometry to render
     *
     * @see Technique
     * @see RenderState
     * @see com.jme3.material.Material#selectTechnique(java.lang.String, com.jme3.renderer.RenderManager)
     * @see com.jme3.material.Material#render(com.jme3.scene.Geometry, com.jme3.renderer.RenderManager)
     */
    public void renderGeometry(Geometry geom) {
        
        if (renderFilter != null && !renderFilter.test(geom)) {
            return;
        }
        
        LightList lightList = geom.getWorldLightList();
        if (lightFilter != null) {
            filteredLightList.clear();
            lightFilter.filterLights(geom, filteredLightList);
            lightList = filteredLightList;
        }

        for (com.jme3.light.Light light : lightList)
            lightsInUse.add(light);

        // Report the number of lights we're about to render to the statistics.
        renderer.getStatistics().onLights(lightList.size());
        renderer.getStatistics().onLightsInUse(lightsInUse.size());

        renderGeometry(geom, lightList);
        
    }
    private HashSet<com.jme3.light.Light> lightsInUse = new HashSet<>();
    
    /**
     * 
     * @param geom
     * @param lightList 
     */
    public void renderGeometry(Geometry geom, LightList lightList) {
        
        if (renderFilter != null && !renderFilter.test(geom)) {
            return;
        }
        
        this.renderer.pushDebugGroup(geom.getName());
        if (geom.isIgnoreTransform()) {
            setWorldMatrix(Matrix4f.IDENTITY);
        } else {
            setWorldMatrix(geom.getWorldMatrix());
        }

        // Use material override to pass the current target index (used in api such as GL ES
        // that do not support glDrawBuffer)
        FrameBuffer currentFb = this.renderer.getCurrentFrameBuffer();
        if (currentFb != null && !currentFb.isMultiTarget()) {
            this.boundDrawBufferId.setValue(currentFb.getTargetIndex());
        }

        Material material = geom.getMaterial();

        // If forcedTechnique exists, we try to force it for the render.
        // If it does not exist in the mat def, we check for forcedMaterial and render the geom if not null.
        // Otherwise, the geometry is not rendered.
        if (forcedTechnique != null) {
            MaterialDef matDef = material.getMaterialDef();
            if (matDef.getTechniqueDefs(forcedTechnique) != null) {

                Technique activeTechnique = material.getActiveTechnique();

                String previousTechniqueName = activeTechnique != null
                        ? activeTechnique.getDef().getName()
                        : TechniqueDef.DEFAULT_TECHNIQUE_NAME;

                geom.getMaterial().selectTechnique(forcedTechnique, this);
                //saving forcedRenderState for future calls
                RenderState tmpRs = forcedRenderState;
                if (geom.getMaterial().getActiveTechnique().getDef().getForcedRenderState() != null) {
                    //forcing forced technique renderState
                    forcedRenderState
                            = geom.getMaterial().getActiveTechnique().getDef().getForcedRenderState();
                }
                // use geometry's material
                material.render(geom, lightList, this);
                material.selectTechnique(previousTechniqueName, this);

                //restoring forcedRenderState
                forcedRenderState = tmpRs;

                //Reverted this part from revision 6197
                // If forcedTechnique does not exist and forcedMaterial is not set,
                // the geometry MUST NOT be rendered.
            } else if (forcedMaterial != null) {
                // use forced material
                forcedMaterial.render(geom, lightList, this);
            }
        } else if (forcedMaterial != null) {
            // use forced material
            forcedMaterial.render(geom, lightList, this);
        } else {
            material.render(geom, lightList, this);
        }
        this.renderer.popDebugGroup();
    }

    /**
     * Renders the given GeometryList.
     *
     * <p>For every geometry in the list, the
     * {@link #renderGeometry(com.jme3.scene.Geometry) } method is called.
     *
     * @param gl The geometry list to render.
     *
     * @see GeometryList
     * @see #renderGeometry(com.jme3.scene.Geometry)
     */
    public void renderGeometryList(GeometryList gl) {
        for (int i = 0; i < gl.size(); i++) {
            renderGeometry(gl.get(i));
        }
    }

    /**
     * Preloads a scene for rendering.
     *
     * <p>After invocation of this method, the underlying
     * renderer would have uploaded any textures, shaders and meshes
     * used by the given scene to the video driver.
     * Using this method is useful when wishing to avoid the initial pause
     * when rendering a scene for the first time. Note that it is not
     * guaranteed that the underlying renderer will actually choose to upload
     * the data to the GPU so some pause is still to be expected.
     *
     * @param scene The scene to preload
     */
    public void preloadScene(Spatial scene) {
        if (scene instanceof Node) {
            // recurse for all children
            Node n = (Node) scene;
            List<Spatial> children = n.getChildren();
            for (int i = 0; i < children.size(); i++) {
                preloadScene(children.get(i));
            }
        } else if (scene instanceof Geometry) {
            // addUserEvent to the render queue
            Geometry gm = (Geometry) scene;
            if (gm.getMaterial() == null) {
                throw new IllegalStateException("No material is set for Geometry: " + gm.getName());
            }

            gm.getMaterial().preload(this, gm);
            Mesh mesh = gm.getMesh();
            if (mesh != null
                    && mesh.getVertexCount() != 0
                    && mesh.getTriangleCount() != 0) {
                for (VertexBuffer vb : mesh.getBufferList().getArray()) {
                    if (vb.getData() != null && vb.getUsage() != VertexBuffer.Usage.CpuOnly) {
                        renderer.updateBufferData(vb);
                    }
                }
            }
        }
    }
    
    /**
     * Flattens the given scene graph into the ViewPort's RenderQueue,
     * checking for culling as the call goes down the graph recursively.
     *
     * <p>First, the scene is checked for culling based on the <code>Spatial</code>s
     * {@link Spatial#setCullHint(com.jme3.scene.Spatial.CullHint) cull hint},
     * if the camera frustum contains the scene, then this method is recursively
     * called on its children.
     *
     * <p>When the scene's leaves or {@link Geometry geometries} are reached,
     * they are each enqueued into the
     * {@link ViewPort#getQueue() ViewPort's render queue}.
     *
     * <p>In addition to enqueuing the visible geometries, this method
     * also scenes which cast or receive shadows, by putting them into the
     * RenderQueue's
     * {@link RenderQueue#addToQueue(com.jme3.scene.Geometry, com.jme3.renderer.queue.RenderQueue.Bucket)
     * shadow queue}. Each Spatial which has its
     * {@link Spatial#setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode) shadow mode}
     * set to not off, will be put into the appropriate shadow queue, note that
     * this process does not check for frustum culling on any
     * {@link ShadowMode#Cast shadow casters}, as they don't have to be
     * in the eye camera frustum to cast shadows on objects that are inside it.
     *
     * @param scene The scene to flatten into the queue
     * @param vp The ViewPort provides the {@link ViewPort#getCamera() camera}
     *     used for culling and the {@link ViewPort#getQueue() queue} used to
     *     contain the flattened scene graph.
     */
    public void renderScene(Spatial scene, ViewPort vp) {
        // reset of the camera plane state for proper culling
        // (must be 0 for the first note of the scene to be rendered)
        vp.getCamera().setPlaneState(0);
        // queue the scene for rendering
        renderSubScene(scene, vp);
    }

    /**
     * Recursively renders the scene.
     *
     * @param scene the scene to be rendered (not null)
     * @param vp the ViewPort to render in (not null)
     */
    private void renderSubScene(Spatial scene, ViewPort vp) {
        // check culling first
        if (!scene.checkCulling(vp.getCamera())) {
            return;
        }
        scene.runControlRender(this, vp);
        if (scene instanceof Node) {
            // Recurse for all children
            Node n = (Node) scene;
            List<Spatial> children = n.getChildren();
            // Saving cam state for culling
            int camState = vp.getCamera().getPlaneState();
            for (int i = 0; i < children.size(); i++) {
                // Restoring cam state before proceeding children recursively
                vp.getCamera().setPlaneState(camState);
                renderSubScene(children.get(i), vp);
            }
        } else if (scene instanceof Geometry) {
            // addUserEvent to the render queue
            Geometry gm = (Geometry) scene;
            if (gm.getMaterial() == null) {
                throw new IllegalStateException("No material is set for Geometry: " + gm.getName());
            }
            vp.getQueue().addToQueue(gm, scene.getQueueBucket());
        }
    }

    /**
     * Returns the camera currently used for rendering.
     *
     * <p>The camera can be set with {@link #setCamera(com.jme3.renderer.Camera, boolean) }.
     *
     * @return the camera currently used for rendering.
     */
    public Camera getCurrentCamera() {
        return prevCam;
    }

    /**
     * The renderer implementation used for rendering operations.
     *
     * @return The renderer implementation
     *
     * @see #RenderManager(com.jme3.renderer.Renderer)
     * @see Renderer
     */
    public Renderer getRenderer() {
        return renderer;
    }

    /**
     * Flushes the ViewPort's {@link ViewPort#getQueue() render queue}
     * by rendering each of its visible buckets.
     * By default, the queues will be cleared automatically after rendering,
     * so there's no need to clear them manually.
     *
     * @param vp The ViewPort of which the queue will be flushed
     *
     * @see RenderQueue#renderQueue(com.jme3.renderer.queue.RenderQueue.Bucket,
     *     com.jme3.renderer.RenderManager, com.jme3.renderer.Camera)
     * @see #renderGeometryList(com.jme3.renderer.queue.GeometryList)
     */
    public void flushQueue(ViewPort vp) {
        renderViewPortQueues(vp, true);
    }

    /**
     * Clears the queue of the given ViewPort.
     * Simply calls {@link RenderQueue#clear() } on the ViewPort's
     * {@link ViewPort#getQueue() render queue}.
     *
     * @param vp The ViewPort of which the queue will be cleared.
     *
     * @see RenderQueue#clear()
     * @see ViewPort#getQueue()
     */
    public void clearQueue(ViewPort vp) {
        vp.getQueue().clear();
        lightsInUse.clear();
    }

    /**
     * Sets the light filter to use when rendering lit Geometries.
     *
     * @see LightFilter
     * @param lightFilter The light filter. Set it to null if you want all lights to be rendered.
     */
    public void setLightFilter(LightFilter lightFilter) {
        this.lightFilter = lightFilter;
    }

    /**
     * Returns the current LightFilter.
     *
     * @return the current light filter
     */
    public LightFilter getLightFilter() {
        return this.lightFilter;
    }

    /**
     * Defines what light mode will be selected when a technique offers several light modes.
     *
     * @param preferredLightMode The light mode to use.
     */
    public void setPreferredLightMode(TechniqueDef.LightMode preferredLightMode) {
        this.preferredLightMode = preferredLightMode;
    }

    /**
     * Returns the preferred light mode.
     *
     * @return the light mode.
     */
    public TechniqueDef.LightMode getPreferredLightMode() {
        return preferredLightMode;
    }

    /**
     * Returns the number of lights used for each pass when the light mode is single pass.
     *
     * @return the number of lights.
     */
    public int getSinglePassLightBatchSize() {
        return singlePassLightBatchSize;
    }

    /**
     * Sets the number of lights to use for each pass when the light mode is single pass.
     *
     * @param singlePassLightBatchSize the number of lights.
     */
    public void setSinglePassLightBatchSize(int singlePassLightBatchSize) {
        // Ensure the batch size is no less than 1
        this.singlePassLightBatchSize = singlePassLightBatchSize < 1 ? 1 : singlePassLightBatchSize;
    }


    /**
     * Renders the given viewport queues.
     *
     * <p>Changes the {@link Renderer#setDepthRange(float, float) depth range}
     * appropriately as expected by each queue and then calls
     * {@link RenderQueue#renderQueue(com.jme3.renderer.queue.RenderQueue.Bucket,
     * com.jme3.renderer.RenderManager, com.jme3.renderer.Camera, boolean) }
     * on the queue. Makes sure to restore the depth range to [0, 1]
     * at the end of the call.
     * Note that the {@link Bucket#Translucent translucent bucket} is NOT
     * rendered by this method. Instead, the user should call
     * {@link #renderTranslucentQueue(com.jme3.renderer.ViewPort) }
     * after this call.
     *
     * @param vp the viewport of which queue should be rendered
     * @param flush If true, the queues will be cleared after
     *     rendering.
     *
     * @see RenderQueue
     * @see #renderTranslucentQueue(com.jme3.renderer.ViewPort)
     */
    public void renderViewPortQueues(ViewPort vp, boolean flush) {
        RenderQueue rq = vp.getQueue();
        Camera cam = vp.getCamera();
        boolean depthRangeChanged = false;

        // render opaque objects with default depth range
        // opaque objects are sorted front-to-back, reducing overdraw
        if (prof != null) {
            prof.vpStep(VpStep.RenderBucket, vp, Bucket.Opaque);
        }
        rq.renderQueue(Bucket.Opaque, this, cam, flush);

        // render the sky, with depth range set to the farthest
        if (!rq.isQueueEmpty(Bucket.Sky)) {
            if (prof != null) {
                prof.vpStep(VpStep.RenderBucket, vp, Bucket.Sky);
            }
            renderer.setDepthRange(1, 1);
            rq.renderQueue(Bucket.Sky, this, cam, flush);
            depthRangeChanged = true;
        }


        // transparent objects are last because they require blending with the
        // rest of the scene's objects. Consequently, they are sorted
        // back-to-front.
        if (!rq.isQueueEmpty(Bucket.Transparent)) {
            if (prof != null) {
                prof.vpStep(VpStep.RenderBucket, vp, Bucket.Transparent);
            }
            if (depthRangeChanged) {
                renderer.setDepthRange(0, 1);
                depthRangeChanged = false;
            }
            rq.renderQueue(Bucket.Transparent, this, cam, flush);
        }

        if (!rq.isQueueEmpty(Bucket.Gui)) {
            if (prof != null) {
                prof.vpStep(VpStep.RenderBucket, vp, Bucket.Gui);
            }
            renderer.setDepthRange(0, 0);
            setCamera(cam, true);
            rq.renderQueue(Bucket.Gui, this, cam, flush);
            setCamera(cam, false);
            depthRangeChanged = true;
        }

        // restore range to default
        if (depthRangeChanged) {
            renderer.setDepthRange(0, 1);
        }
    }

    /**
     * Renders the {@link Bucket#Translucent translucent queue} on the viewPort.
     *
     * <p>This call does nothing unless {@link #setHandleTranslucentBucket(boolean) }
     * is set to true. This method clears the translucent queue after rendering
     * it.
     *
     * @param vp The viewport of which the translucent queue should be rendered.
     *
     * @see #renderViewPortQueues(com.jme3.renderer.ViewPort, boolean)
     * @see #setHandleTranslucentBucket(boolean)
     */
    public void renderTranslucentQueue(ViewPort vp) {
        if (prof != null) {
            prof.vpStep(VpStep.RenderBucket, vp, Bucket.Translucent);
        }

        RenderQueue rq = vp.getQueue();
        if (!rq.isQueueEmpty(Bucket.Translucent) && handleTranslucentBucket) {
            rq.renderQueue(Bucket.Translucent, this, vp.getCamera(), true);
        }
    }

    private void setViewPort(Camera cam) {
        // this will make sure to clearReservations viewport only if needed
        if (cam != prevCam || cam.isViewportChanged()) {
            viewX      = (int) (cam.getViewPortLeft() * cam.getWidth());
            viewY      = (int) (cam.getViewPortBottom() * cam.getHeight());
            int viewX2 = (int) (cam.getViewPortRight() * cam.getWidth());
            int viewY2 = (int) (cam.getViewPortTop() * cam.getHeight());
            viewWidth  = viewX2 - viewX;
            viewHeight = viewY2 - viewY;
            uniformBindingManager.setViewPort(viewX, viewY, viewWidth, viewHeight);
            renderer.setViewPort(viewX, viewY, viewWidth, viewHeight);
            renderer.setClipRect(viewX, viewY, viewWidth, viewHeight);
            cam.clearViewportChanged();
            prevCam = cam;

//            float translateX = viewWidth == viewX ? 0 : -(viewWidth + viewX) / (viewWidth - viewX);
//            float translateY = viewHeight == viewY ? 0 : -(viewHeight + viewY) / (viewHeight - viewY);
//            float scaleX = viewWidth == viewX ? 1f : 2f / (viewWidth - viewX);
//            float scaleY = viewHeight == viewY ? 1f : 2f / (viewHeight - viewY);
//
//            orthoMatrix.loadIdentity();
//            orthoMatrix.setTranslation(translateX, translateY, 0);
//            orthoMatrix.setScale(scaleX, scaleY, 0);

            orthoMatrix.loadIdentity();
            orthoMatrix.setTranslation(-1f, -1f, 0f);
            orthoMatrix.setScale(2f / cam.getWidth(), 2f / cam.getHeight(), 0f);
        }
    }

    private void setViewProjection(Camera cam, boolean ortho) {
        if (ortho) {
            uniformBindingManager.setCamera(cam, Matrix4f.IDENTITY, orthoMatrix, orthoMatrix);
        } else {
            uniformBindingManager.setCamera(cam, cam.getViewMatrix(), cam.getProjectionMatrix(),
                    cam.getViewProjectionMatrix());
        }
    }

    /**
     * Sets the camera to use for rendering.
     *
     * <p>First, the camera's
     * {@link Camera#setViewPort(float, float, float, float) view port parameters}
     * are applied. Then, the camera's {@link Camera#getViewMatrix() view} and
     * {@link Camera#getProjectionMatrix() projection} matrices are set
     * on the renderer. If <code>ortho</code> is <code>true</code>, then
     * instead of using the camera's view and projection matrices, an ortho
     * matrix is computed and used instead of the view projection matrix.
     * The ortho matrix converts from the range (0 ~ Width, 0 ~ Height, -1 ~ +1)
     * to the clip range (-1 ~ +1, -1 ~ +1, -1 ~ +1).
     *
     * @param cam The camera to set
     * @param ortho True if to use orthographic projection (for GUI rendering),
     *     false if to use the camera's view and projection matrices.
     */
    public void setCamera(Camera cam, boolean ortho) {
        // Tell the light filter which camera to use for filtering.
        if (lightFilter != null) {
            lightFilter.setCamera(cam);
        }
        setViewPort(cam);
        setViewProjection(cam, ortho);
    }

    /**
     * Draws the viewport but without notifying {@link SceneProcessor scene
     * processors} of any rendering events.
     *
     * @param vp The ViewPort to render
     *
     * @see #renderViewPort(com.jme3.renderer.ViewPort, float)
     */
    public void renderViewPortRaw(ViewPort vp) {
        setCamera(vp.getCamera(), false);
        List<Spatial> scenes = vp.getScenes();
        for (int i = scenes.size() - 1; i >= 0; i--) {
            renderScene(scenes.get(i), vp);
        }
        flushQueue(vp);
    }

    /**
     * Applies the ViewPort's Camera and FrameBuffer in preparation
     * for rendering.
     * 
     * @param vp 
     */
    public void applyViewPort(ViewPort vp) {
        renderer.setFrameBuffer(vp.getOutputFrameBuffer());
        setCamera(vp.getCamera(), false);
        if (vp.isClearDepth() || vp.isClearColor() || vp.isClearStencil()) {
            if (vp.isClearColor()) {
                renderer.setBackgroundColor(vp.getBackgroundColor());
            }
            renderer.clearBuffers(vp.isClearColor(), vp.isClearDepth(), vp.isClearStencil());
        }
    }
    
    /**
     * Renders the {@link ViewPort} using the ViewPort's {@link RenderPipeline}.
     * <p>
     * If the ViewPort's RenderPipeline is null, the pipeline returned by
     * {@link #getPipeline()} is used instead.
     * <p>
     * If the ViewPort is disabled, no rendering will occur.
     *
     * @param vp View port to render
     * @param tpf Time per frame value
     */
    public void renderViewPort(ViewPort vp, float tpf) {
        if (!vp.isEnabled()) {
            return;
        }        
        RenderPipeline pipeline = vp.getPipeline();
        if (pipeline == null) {
            pipeline = defaultPipeline;
        }
        PipelineContext context = pipeline.fetchPipelineContext(this);
        if (context == null) {
            throw new NullPointerException("Failed to fetch pipeline context.");
        }
        if (!context.startViewPortRender(this, vp)) {
            usedContexts.add(context);
        }
        if (!pipeline.hasRenderedThisFrame()) {
            usedPipelines.add(pipeline);
            pipeline.startRenderFrame(this);
        }
        pipeline.pipelineRender(this, context, vp, tpf);
        context.endViewPortRender(this, vp);
    }

    /**
     * Called by the application to render any ViewPorts
     * added to this RenderManager.
     *
     * <p>Renders any viewports that were added using the following methods:
     * <ul>
     * <li>{@link #createPreView(java.lang.String, com.jme3.renderer.Camera) }</li>
     * <li>{@link #createMainView(java.lang.String, com.jme3.renderer.Camera) }</li>
     * <li>{@link #createPostView(java.lang.String, com.jme3.renderer.Camera) }</li>
     * </ul>
     *
     * @param tpf Time per frame value
     * @param mainFrameBufferActive true to render viewports with no output
     *     FrameBuffer, false to skip them
     */
    public void render(float tpf, boolean mainFrameBufferActive) {
        if (renderer instanceof NullRenderer) {
            return;
        }
        
        uniformBindingManager.newFrame();

        if (prof != null) {
            prof.appStep(AppStep.RenderPreviewViewPorts);
        }
        for (int i = 0; i < preViewPorts.size(); i++) {
            ViewPort vp = preViewPorts.get(i);
            if (vp.getOutputFrameBuffer() != null || mainFrameBufferActive) {
                renderViewPort(vp, tpf);
            }
        }

        if (prof != null) {
            prof.appStep(AppStep.RenderMainViewPorts);
        }
        for (int i = 0; i < viewPorts.size(); i++) {
            ViewPort vp = viewPorts.get(i);
            if (vp.getOutputFrameBuffer() != null || mainFrameBufferActive) {
                renderViewPort(vp, tpf);
            }
        }

        if (prof != null) {
            prof.appStep(AppStep.RenderPostViewPorts);
        }
        for (int i = 0; i < postViewPorts.size(); i++) {
            ViewPort vp = postViewPorts.get(i);
            if (vp.getOutputFrameBuffer() != null || mainFrameBufferActive) {
                renderViewPort(vp, tpf);
            }
        }
        
        // cleanup for used render pipelines and pipeline contexts only
        for (PipelineContext c : usedContexts) {
            c.endContextRenderFrame(this);
        }
        for (RenderPipeline p : usedPipelines) {
            p.endRenderFrame(this);
        }
        usedContexts.clear();
        usedPipelines.clear();
        
    }

    /**
     * Returns true if the draw buffer target id is passed to the shader.
     *
     * @return True if the draw buffer target id is passed to the shaders.
     */
    public boolean getPassDrawBufferTargetIdToShaders() {
        return this.forcedOverrides.contains(boundDrawBufferId);
    }

    /**
     * Enable or disable passing the draw buffer target id to the shaders. This
     * is needed to handle FrameBuffer.setTargetIndex correctly in some
     * backends.
     *
     * @param v
     *            True to enable, false to disable (default is true)
     */
    public void setPassDrawBufferTargetIdToShaders(boolean v) {
        if (v) {
            if (!this.forcedOverrides.contains(boundDrawBufferId)) {
                this.forcedOverrides.add(boundDrawBufferId);
            }
        } else {
            this.forcedOverrides.remove(boundDrawBufferId);
        }
    }
    
    /**
     * Set a render filter. Every geometry will be tested against this filter
     * before rendering and will only be rendered if the filter returns true.
     * 
     * @param filter the render filter
     */
    public void setRenderFilter(Predicate<Geometry> filter) {
        renderFilter = filter;
    }

    /**
     * Returns the render filter that the RenderManager is currently using
     * 
     * @return the render filter 
     */
    public Predicate<Geometry> getRenderFilter() {
        return renderFilter;
    }

}
