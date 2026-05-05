package com.drskunk.sh3dikea;

import com.eteks.sweethome3d.plugin.Plugin;
import com.eteks.sweethome3d.plugin.PluginAction;

public class IkeaImportPlugin extends Plugin {
    @Override
    public PluginAction[] getActions() {
        return new PluginAction[] { new IkeaImportAction(this) };
    }
}
