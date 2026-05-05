package com.drskunk.sh3dikea;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.viewcontroller.HomeController;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modal dialog for searching IKEA's catalog and adding selected items to the
 * current home. Layout:
 *
 *   [country] [language] [search field] [Search]
 *   ------------------------------------------------
 *   |  [thumb]      [thumb]      [thumb]            |
 *   |  name         name         name               |
 *   |  [Add]        [Add]        [Add]              |
 *   |  ...                                          |
 *   ------------------------------------------------
 *                                            [Close]
 */
public final class IkeaBrowserDialog extends JDialog {

    private static final int THUMB_PX = 160;
    private static final int COLUMNS = 3;

    private final Home home;
    private final HomeController homeController;
    private final IkeaSettings settings;
    private final IkeaCache cache;

    private IkeaApiClient api;
    private IkeaImporter importer;

    private final JTextField countryField;
    private final JTextField languageField;
    private final JTextField searchField;
    private final JButton searchButton;
    private final JPanel resultsPanel;
    private final JLabel statusLabel;

    private final ConcurrentHashMap<String, ImageIcon> iconCache = new ConcurrentHashMap<>();

    public IkeaBrowserDialog(Component parent, Home home, HomeController homeController) {
        super(SwingUtilities.getWindowAncestor(parent), "IKEA Browser", Dialog.ModalityType.MODELESS);
        this.home = home;
        this.homeController = homeController;
        this.settings = new IkeaSettings();
        try {
            this.cache = new IkeaCache();
        } catch (Exception e) {
            throw new RuntimeException("Could not create IKEA cache directory", e);
        }
        rebuildClient();
        IkeaLog.info("Browser opened. Draco available="
                + com.drskunk.sh3dikea.draco.DracoNative.isAvailable()
                + ", os.name=" + System.getProperty("os.name")
                + ", os.arch=" + System.getProperty("os.arch")
                + ", java.version=" + System.getProperty("java.version"));

        setLayout(new BorderLayout(0, 0));

        countryField = new JTextField(settings.getCountry(), 3);
        languageField = new JTextField(settings.getLanguage(), 3);
        searchField = new JTextField(20);
        searchButton = new JButton("Search");

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        top.add(new JLabel("Country:"));
        top.add(countryField);
        top.add(new JLabel("Language:"));
        top.add(languageField);
        top.add(Box.createHorizontalStrut(8));
        top.add(new JLabel("Search:"));
        top.add(searchField);
        top.add(searchButton);
        add(top, BorderLayout.NORTH);

        resultsPanel = new JPanel();
        resultsPanel.setLayout(new GridLayout(0, COLUMNS, 8, 8));
        resultsPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(resultsPanel);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(720, 520));
        add(scroll, BorderLayout.CENTER);

        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        // Status in CENTER so it wraps/clips on long messages instead of
        // shoving the Close button off the dialog.
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(statusLabel, BorderLayout.CENTER);
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel rightBottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        rightBottom.add(close);
        bottom.add(rightBottom, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        ActionListener doSearch = e -> startSearch();
        searchButton.addActionListener(doSearch);
        searchField.addActionListener(doSearch);

        // ESC closes; ENTER searches when focus is on country / language too.
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        countryField.addActionListener(doSearch);
        languageField.addActionListener(doSearch);

        pack();
        setLocationRelativeTo(parent);
    }

    private void rebuildClient() {
        this.api = new IkeaApiClient(settings.getCountry(), settings.getLanguage());
        this.importer = new IkeaImporter(api, cache);
    }

    private void startSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) return;

        // Persist any country/language change before searching.
        settings.update(countryField.getText(), languageField.getText());
        countryField.setText(settings.getCountry());
        languageField.setText(settings.getLanguage());
        rebuildClient();

        searchButton.setEnabled(false);
        statusLabel.setText("Searching for \"" + query + "\"…");
        resultsPanel.removeAll();
        resultsPanel.revalidate();
        resultsPanel.repaint();

        new SwingWorker<List<IkeaProduct>, Void>() {
            @Override
            protected List<IkeaProduct> doInBackground() throws Exception {
                return api.search(query);
            }

            @Override
            protected void done() {
                searchButton.setEnabled(true);
                try {
                    List<IkeaProduct> results = get();
                    if (results.isEmpty()) {
                        statusLabel.setText("No results for \"" + query + "\"");
                        return;
                    }
                    statusLabel.setText(results.size() + " variant(s). Each tile is a colour/size — "
                            + "refine the search (e.g. \"kallax white\") to narrow.");
                    for (IkeaProduct p : results) {
                        resultsPanel.add(buildTile(p));
                    }
                    resultsPanel.revalidate();
                    resultsPanel.repaint();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    statusLabel.setText("Search failed: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private JComponent buildTile(IkeaProduct p) {
        JPanel tile = new JPanel();
        tile.setLayout(new BoxLayout(tile, BoxLayout.Y_AXIS));
        tile.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xCCCCCC)),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));

        JLabel image = new JLabel("Loading…", JLabel.CENTER);
        image.setPreferredSize(new Dimension(THUMB_PX, THUMB_PX));
        image.setMaximumSize(new Dimension(THUMB_PX, THUMB_PX));
        image.setMinimumSize(new Dimension(THUMB_PX, THUMB_PX));
        image.setAlignmentX(Component.CENTER_ALIGNMENT);
        tile.add(image);

        // HTML width matches the image so long localised type names wrap
        // inside the tile instead of widening it.
        JLabel name = centered("<html><div style='width:" + THUMB_PX
                + "px;text-align:center;'>"
                + "<b>" + escapeHtml(p.name == null ? p.itemNo : p.name) + "</b>"
                + (p.typeName != null && !p.typeName.isEmpty()
                        ? "<br><span style='color:#555'>" + escapeHtml(p.typeName) + "</span>"
                        : "")
                + "</div></html>");
        // Stop the label growing past the image width when text is short.
        name.setMaximumSize(new Dimension(THUMB_PX, Short.MAX_VALUE));
        tile.add(Box.createVerticalStrut(4));
        tile.add(name);

        String detail = IkeaProduct.formatItemNo(p.itemNo);
        if (p.measureRef != null && !p.measureRef.isEmpty()) {
            detail += "  ·  " + p.measureRef;
        }
        JLabel item = centered(detail);
        item.setForeground(new Color(0x666666));
        tile.add(item);

        JButton add = new JButton("Add to home");
        add.setAlignmentX(Component.CENTER_ALIGNMENT);
        add.addActionListener(e -> doAdd(add, p));
        tile.add(Box.createVerticalStrut(4));
        tile.add(add);

        loadThumbAsync(p, image);
        return tile;
    }

    private static JLabel centered(String text) {
        JLabel l = new JLabel(text, JLabel.CENTER);
        l.setHorizontalAlignment(JLabel.CENTER);
        l.setAlignmentX(Component.CENTER_ALIGNMENT);
        return l;
    }

    private void loadThumbAsync(IkeaProduct p, JLabel target) {
        ImageIcon cached = iconCache.get(p.itemNo);
        if (cached != null) {
            target.setIcon(cached);
            target.setText(null);
            return;
        }
        new SwingWorker<ImageIcon, Void>() {
            @Override
            protected ImageIcon doInBackground() throws Exception {
                File thumbFile = cache.thumbnailFile(p.itemNo);
                if (!thumbFile.exists() && p.mainImageUrl != null) {
                    byte[] data = api.download(p.mainImageUrl);
                    IkeaCache.writeAtomically(thumbFile, data);
                }
                if (!thumbFile.exists()) return null;
                ImageIcon raw = new ImageIcon(thumbFile.getAbsolutePath());
                Image scaled = raw.getImage().getScaledInstance(THUMB_PX, THUMB_PX, Image.SCALE_SMOOTH);
                return new ImageIcon(scaled);
            }

            @Override
            protected void done() {
                try {
                    ImageIcon icon = get();
                    if (icon != null) {
                        iconCache.put(p.itemNo, icon);
                        target.setIcon(icon);
                        target.setText(null);
                    } else {
                        target.setText("(no image)");
                    }
                } catch (Exception ex) {
                    target.setText("(image error)");
                }
            }
        }.execute();
    }

    private void doAdd(JButton button, IkeaProduct p) {
        button.setEnabled(false);
        String original = button.getText();
        button.setText("Importing…");
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        new SwingWorker<IkeaImporter.Prepared, Void>() {
            @Override
            protected IkeaImporter.Prepared doInBackground() throws Exception {
                return importer.prepare(p);
            }

            @Override
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                button.setEnabled(true);
                button.setText(original);
                try {
                    IkeaImporter.Prepared prepared = get();
                    importer.addToHome(homeController, home, prepared);
                    statusLabel.setText("Added \"" + p.name + "\" to home.");
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(IkeaBrowserDialog.this,
                            "Could not import this item:\n" + cause.getMessage(),
                            "IKEA Browser", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}
