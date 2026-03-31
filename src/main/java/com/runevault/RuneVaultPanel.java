package com.runevault;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

public class RuneVaultPanel extends PluginPanel
{
    private static final Color COLOR_GOLD         = new Color(0xe8, 0xa0, 0x60);
    private static final Color COLOR_CONNECTED    = new Color(0x40, 0xc0, 0x70);
    private static final Color COLOR_DISCONNECTED = new Color(0xc0, 0x50, 0x50);
    private static final Color COLOR_WARNING      = new Color(0xe0, 0xb0, 0x40);

    // Status row
    private final JLabel statusDot  = new JLabel("\u25cf"); // filled circle
    private final JLabel statusText = new JLabel("Not connected");
    private final JLabel profileLabel = new JLabel(" ");

    // Connect form
    private final JTextField codeField    = new JTextField();
    private final JButton    connectBtn   = new JButton("Connect");
    private final JLabel     feedbackLabel = new JLabel(" ");

    // Disconnect
    private final JButton disconnectBtn = new JButton("Disconnect");

    private final RuneVaultConfig config;

    private final java.util.function.Consumer<Boolean> onPublicToggle;

    RuneVaultPanel(ActionListener onConnect, ActionListener onDisconnect, RuneVaultConfig config,
                   java.util.function.Consumer<Boolean> onPublicToggle)
    {
        this.config         = config;
        this.onPublicToggle = onPublicToggle;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // Allow pressing Enter in the code field to trigger connect
        codeField.addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) onConnect.actionPerformed(null);
            }
        });

        connectBtn.addActionListener(onConnect);
        disconnectBtn.addActionListener(onDisconnect);

        add(buildContent(), BorderLayout.NORTH);
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private JPanel buildContent()
    {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setOpaque(false);

        // ── Title ────────────────────────────────────────────────────────────
        JLabel title = new JLabel("Rune Vault");
        title.setForeground(COLOR_GOLD);
        title.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        root.add(title);
        root.add(Box.createVerticalStrut(10));

        // ── Status card ──────────────────────────────────────────────────────
        JPanel statusCard = buildCard();
        statusCard.setLayout(new BoxLayout(statusCard, BoxLayout.Y_AXIS));

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        statusRow.setOpaque(false);
        statusDot.setFont(new Font("Monospaced", Font.PLAIN, 13));
        statusDot.setForeground(COLOR_DISCONNECTED);
        statusText.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        statusText.setFont(FontManager.getRunescapeSmallFont());
        statusRow.add(statusDot);
        statusRow.add(statusText);
        statusRow.setAlignmentX(LEFT_ALIGNMENT);
        statusCard.add(statusRow);

        profileLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        profileLabel.setFont(FontManager.getRunescapeSmallFont());
        profileLabel.setAlignmentX(LEFT_ALIGNMENT);
        statusCard.add(profileLabel);

        root.add(statusCard);
        root.add(Box.createVerticalStrut(8));

        // ── Connect card ─────────────────────────────────────────────────────
        JPanel connectCard = buildCard();
        connectCard.setLayout(new BoxLayout(connectCard, BoxLayout.Y_AXIS));

        JLabel codeLabel = new JLabel("Link Code");
        codeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        codeLabel.setFont(FontManager.getRunescapeSmallFont());
        codeLabel.setAlignmentX(LEFT_ALIGNMENT);
        connectCard.add(codeLabel);
        connectCard.add(Box.createVerticalStrut(4));

        codeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        codeField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        codeField.setForeground(Color.WHITE);
        codeField.setCaretColor(Color.WHITE);
        codeField.setFont(new Font("Monospaced", Font.BOLD, 15));
        codeField.setHorizontalAlignment(JTextField.CENTER);
        codeField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(4, 6, 4, 6)
        ));
        codeField.setAlignmentX(LEFT_ALIGNMENT);
        connectCard.add(codeField);
        connectCard.add(Box.createVerticalStrut(6));

        styleButton(connectBtn, COLOR_GOLD, new Color(0x1a, 0x0a, 0x00));
        connectBtn.setAlignmentX(LEFT_ALIGNMENT);
        connectBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        connectCard.add(connectBtn);

        feedbackLabel.setFont(FontManager.getRunescapeSmallFont());
        feedbackLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        feedbackLabel.setAlignmentX(LEFT_ALIGNMENT);
        feedbackLabel.setBorder(new EmptyBorder(4, 0, 0, 0));
        connectCard.add(feedbackLabel);

        root.add(connectCard);
        root.add(Box.createVerticalStrut(8));

        // ── Disconnect button ────────────────────────────────────────────────
        styleButton(disconnectBtn, new Color(0x55, 0x15, 0x15), new Color(0xff, 0x88, 0x88));
        disconnectBtn.setAlignmentX(LEFT_ALIGNMENT);
        disconnectBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        disconnectBtn.setVisible(false);
        root.add(disconnectBtn);

        // ── Sync toggles ─────────────────────────────────────────────────────
        root.add(Box.createVerticalStrut(8));
        root.add(buildSyncCard());

        // ── Open portfolio in browser ─────────────────────────────────────────
        root.add(Box.createVerticalStrut(8));
        JButton openWebBtn = new JButton("\uD83C\uDF10  Open Portfolio");
        styleButton(openWebBtn, new Color(0x25, 0x25, 0x25), new Color(0xa0, 0xa0, 0xa0));
        openWebBtn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(4, 8, 4, 8)
        ));
        openWebBtn.setBorderPainted(true);
        openWebBtn.setAlignmentX(LEFT_ALIGNMENT);
        openWebBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        openWebBtn.addActionListener(e -> LinkBrowser.browse("https://runevault.vaultek.co"));
        root.add(openWebBtn);

        // ── Hint ─────────────────────────────────────────────────────────────
        root.add(Box.createVerticalStrut(12));
        JLabel hint = new JLabel("<html><body style='color:#888;width:160px'>"
            + "Generate a code in the Rune Vault app under Settings \u2192 Connect RuneLite Plugin."
            + "</body></html>");
        hint.setFont(FontManager.getRunescapeSmallFont());
        hint.setAlignmentX(LEFT_ALIGNMENT);
        root.add(hint);

        return root;
    }

    private JPanel buildSyncCard()
    {
        JPanel card = buildCard();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JLabel heading = new JLabel("SYNC SETTINGS");
        heading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        heading.setFont(FontManager.getRunescapeSmallFont());
        heading.setAlignmentX(LEFT_ALIGNMENT);
        card.add(heading);
        card.add(Box.createVerticalStrut(6));

        card.add(buildToggle("GE Trades",      config.syncGeTrades(),       v -> config.setSyncGeTrades(v)));
        card.add(Box.createVerticalStrut(3));
        card.add(buildToggle("Item Pickups",   config.trackPickups(),        v -> config.setTrackPickups(v)));
        card.add(Box.createVerticalStrut(3));
        card.add(buildToggle("Drops & Sales",  config.trackDropsAndSales(),  v -> config.setTrackDropsAndSales(v)));
        card.add(Box.createVerticalStrut(3));
        card.add(buildToggle("Cash Stack",     config.syncCash(),            v -> config.setSyncCash(v)));
        card.add(Box.createVerticalStrut(3));
        card.add(buildToggle("Bank Scan",      config.bankScanEnabled(),     v -> config.setBankScanEnabled(v)));

        // ── Privacy divider ───────────────────────────────────────────────────
        card.add(Box.createVerticalStrut(8));
        JSeparator sep = new JSeparator();
        sep.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(LEFT_ALIGNMENT);
        card.add(sep);
        card.add(Box.createVerticalStrut(6));

        JLabel privacyHeading = new JLabel("PRIVACY");
        privacyHeading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        privacyHeading.setFont(FontManager.getRunescapeSmallFont());
        privacyHeading.setAlignmentX(LEFT_ALIGNMENT);
        card.add(privacyHeading);
        card.add(Box.createVerticalStrut(4));

        card.add(buildToggle("Public Portfolio", config.publicProfile(), v -> {
            config.setPublicProfile(v);
            if (onPublicToggle != null) onPublicToggle.accept(v);
        }));

        return card;
    }

    private JPanel buildToggle(String label, boolean initialValue, java.util.function.Consumer<Boolean> onToggle)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        row.setAlignmentX(LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        lbl.setFont(FontManager.getRunescapeSmallFont());

        JCheckBox box = new JCheckBox();
        box.setSelected(initialValue);
        box.setOpaque(false);
        box.setFocusPainted(false);
        box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        box.addActionListener(e -> onToggle.accept(box.isSelected()));

        row.add(lbl,  BorderLayout.CENTER);
        row.add(box,  BorderLayout.EAST);
        return row;
    }

    private JPanel buildCard()
    {
        JPanel card = new JPanel();
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            new EmptyBorder(8, 8, 8, 8)
        ));
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
        return card;
    }

    private void styleButton(JButton btn, Color bg, Color fg)
    {
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setOpaque(true);
    }

    // -------------------------------------------------------------------------
    // State update API — all methods are EDT-safe
    // -------------------------------------------------------------------------

    /** Returns the code the user typed, trimmed and uppercased. */
    public String getCode()
    {
        return codeField.getText().trim().toUpperCase();
    }

    public void setConnecting()
    {
        SwingUtilities.invokeLater(() ->
        {
            connectBtn.setEnabled(false);
            connectBtn.setText("Connecting\u2026");
            feedbackLabel.setText(" ");
            feedbackLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        });
    }

    public void setConnected(String playerName)
    {
        SwingUtilities.invokeLater(() ->
        {
            statusDot.setForeground(COLOR_CONNECTED);
            statusText.setText("Connected");
            profileLabel.setText(playerName != null && !playerName.isEmpty()
                ? "Playing as: " + playerName : " ");
            codeField.setText("");
            connectBtn.setEnabled(true);
            connectBtn.setText("Re-link");
            feedbackLabel.setText(" ");
            disconnectBtn.setVisible(true);
        });
    }

    public void setDisconnected()
    {
        SwingUtilities.invokeLater(() ->
        {
            statusDot.setForeground(COLOR_DISCONNECTED);
            statusText.setText("Not connected");
            profileLabel.setText(" ");
            connectBtn.setEnabled(true);
            connectBtn.setText("Connect");
            feedbackLabel.setText(" ");
            disconnectBtn.setVisible(false);
        });
    }

    public void setFeedback(String message, boolean isError)
    {
        SwingUtilities.invokeLater(() ->
        {
            feedbackLabel.setForeground(isError ? COLOR_DISCONNECTED : COLOR_CONNECTED);
            feedbackLabel.setText(message);
            connectBtn.setEnabled(true);
            connectBtn.setText(isError ? "Connect" : "Re-link");
        });
    }

    public void updatePlayerName(String playerName)
    {
        SwingUtilities.invokeLater(() ->
            profileLabel.setText(playerName != null && !playerName.isEmpty()
                ? "Playing as: " + playerName : " ")
        );
    }

    // -------------------------------------------------------------------------
    // Nav icon — a simple 16x16 gold "RV" badge
    // -------------------------------------------------------------------------

    public static java.awt.image.BufferedImage buildNavIcon()
    {
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xe8, 0xa0, 0x60));
        g.fillRoundRect(0, 0, 16, 16, 5, 5);
        g.setColor(new Color(0x1a, 0x0a, 0x00));
        g.setFont(new Font("Dialog", Font.BOLD, 8));
        FontMetrics fm = g.getFontMetrics();
        String text = "RV";
        g.drawString(text, (16 - fm.stringWidth(text)) / 2, (16 + fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
        return img;
    }
}
