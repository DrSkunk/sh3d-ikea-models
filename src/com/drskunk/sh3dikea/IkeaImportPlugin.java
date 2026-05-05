package com.drskunk.sh3dikea;

import com.eteks.sweethome3d.plugin.Plugin;
import com.eteks.sweethome3d.plugin.PluginAction;

import javax.imageio.ImageIO;

public class IkeaImportPlugin extends Plugin {
    @Override
    public void init() {
        // The bundled TwelveMonkeys WebP reader registers itself via
        // META-INF/services. ImageIO usually scans only the system class
        // loader at boot — Sweet Home 3D loads us afterwards from a separate
        // plugin classloader, so the SPIs aren't visible until we ask
        // ImageIO to rescan. Doing it once in init() keeps the call cheap
        // and ensures the WebP reader is available by the time the import
        // dialog runs.
        ImageIO.setUseCache(false);
        Thread.currentThread().setContextClassLoader(getPluginClassLoader());
        ImageIO.scanForPlugins();
    }

    @Override
    public PluginAction[] getActions() {
        return new PluginAction[] { new IkeaImportAction(this) };
    }
}
