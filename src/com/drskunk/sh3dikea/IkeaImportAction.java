package com.drskunk.sh3dikea;

import com.eteks.sweethome3d.plugin.PluginAction;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;

public final class IkeaImportAction extends PluginAction {

    private static final String MENU = "Furniture";
    private static final String NAME = "Import IKEA model…";

    private final IkeaImportPlugin plugin;
    private IkeaBrowserDialog dialog;

    public IkeaImportAction(IkeaImportPlugin plugin) {
        this.plugin = plugin;
        putPropertyValue(Property.NAME, NAME);
        putPropertyValue(Property.MENU, MENU);
        setEnabled(true);
    }

    @Override
    public void execute() {
        Component parent = findParentComponent();
        try {
            if (dialog == null || !dialog.isDisplayable()) {
                dialog = new IkeaBrowserDialog(parent, plugin.getHome(), plugin.getHomeController());
            }
            dialog.setVisible(true);
            dialog.toFront();
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(parent,
                    "IKEA Browser could not start:\n" + e.getMessage(),
                    "IKEA Browser", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Component findParentComponent() {
        Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (focused != null) return focused;
        return SwingUtilities.getWindowAncestor(null);
    }
}
