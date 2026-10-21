package com.drskunk.sh3dikea.netdiag;

import com.eteks.sweethome3d.plugin.Plugin;
import com.eteks.sweethome3d.plugin.PluginAction;
import com.eteks.sweethome3d.tools.OperatingSystem;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Adds "Tools > IKEA network diagnostics…" to Sweet Home 3D. */
public class NetDiagPlugin extends Plugin {

    @Override
    public PluginAction[] getActions() {
        return new PluginAction[] {new Action()};
    }

    private static final class Action extends PluginAction {
        Action() {
            putPropertyValue(Property.NAME, "IKEA network diagnostics…");
            putPropertyValue(Property.MENU, "Tools");
            setEnabled(true);
        }

        @Override
        public void execute() {
            Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            Dialog d = new Dialog(owner);
            d.setLocationRelativeTo(owner);
            d.setVisible(true);
        }
    }

    private static final class Dialog extends JDialog {
        private final JTextArea area = new JTextArea();
        private final JTextField extraHosts = new JTextField(30);
        private final JButton run = new JButton("Run diagnostics");
        private final JButton copy = new JButton("Copy report");
        private final JLabel status = new JLabel(" ");

        Dialog(Window owner) {
            super(owner, "IKEA network diagnostics", ModalityType.MODELESS);
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane scroll = new JScrollPane(area);
            scroll.setPreferredSize(new Dimension(900, 600));

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
            top.add(new JLabel("Extra hosts (space separated):"));
            top.add(extraHosts);
            top.add(run);
            top.add(copy);

            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            bottom.add(status, BorderLayout.CENTER);

            getContentPane().add(top, BorderLayout.NORTH);
            getContentPane().add(scroll, BorderLayout.CENTER);
            getContentPane().add(bottom, BorderLayout.SOUTH);
            pack();

            run.addActionListener(e -> start());
            copy.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(area.getText()), null);
                status.setText("Report copied to clipboard.");
            });
            copy.setEnabled(false);
        }

        private void start() {
            run.setEnabled(false);
            copy.setEnabled(false);
            area.setText("");
            status.setText("Running… this can take a minute when hosts time out.");
            final List<String> extra = Arrays.asList(extraHosts.getText().trim().split("\\s+"));
            new SwingWorker<Void, String>() {
                @Override
                protected Void doInBackground() {
                    new NetDiag(this::publish).runAll(extra);
                    return null;
                }

                @Override
                protected void process(List<String> lines) {
                    for (String l : lines) area.append(l + "\n");
                }

                @Override
                protected void done() {
                    run.setEnabled(true);
                    copy.setEnabled(true);
                    File f = save(area.getText());
                    status.setText(f != null ? "Done. Report saved to " + f.getAbsolutePath()
                            : "Done. (Could not save report file; use Copy report.)");
                    SwingUtilities.invokeLater(() -> area.setCaretPosition(area.getDocument().getLength()));
                }
            }.execute();
        }

        private static File save(String text) {
            try {
                File dir = new File(OperatingSystem.getDefaultApplicationFolder(), "IkeaBrowser");
                if (!dir.exists() && !dir.mkdirs()) return null;
                File f = new File(dir, "netdiag-report.txt");
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                    w.write(text);
                }
                return f;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
