package com.shatteredpixel.shatteredpixeldungeon.desktop;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/** Public AI messages and action records. Never requests window activation on updates. */
public final class AiConversation {
    private static final List<Map<String,Object>> entries = new ArrayList<>();
    private static final List<Map<String,Object>> history = new ArrayList<>();
    private static boolean historyLoaded;
    private static final String profile = System.getProperty("Specification-Title", "desktop");
    private static final String session = UUID.randomUUID().toString();
    private static long sequence;
    private static long lastAssistant;
    private static long playerReadUpTo;
    private static JFrame window;
    private static JEditorPane transcript;
    private static JScrollPane scroll;
    private static JTextArea input;
    private static JLabel hint;
    private static JCheckBox records;
    private static JButton latest;
    private static int unread;
    private static long renderedSequence = -1;
    private static final Color INK = new Color(39, 55, 65);
    private static final Color PAPER = new Color(237, 242, 244);
    private static JLabel connection;
    private static JLabel gameState;
    private static JCheckBox paused;
    private static String lastGameState = "";
    private static final Path log = Paths.get(System.getProperty("user.dir"), "ai-probe", "dialogue.jsonl");

    public static void open() {
        loadHistory();
        SwingUtilities.invokeLater(() -> {
            if (window != null) { window.setVisible(true); return; }
            window = new JFrame("地牢 AI 对话");
            window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            window.setAutoRequestFocus(false);
            window.setAlwaysOnTop(false);
            JPanel content = new JPanel(new BorderLayout(0, 0));
            content.setBackground(PAPER);
            JPanel head = new JPanel(new BorderLayout(0, 13));
            head.setBackground(INK);
            head.setBorder(new EmptyBorder(20, 22, 18, 22));
            JPanel titles = new JPanel(new GridLayout(0, 1, 0, 7));
            titles.setOpaque(false);
            JLabel title = label("地牢 AI 对话", 24, new Color(245, 247, 248));
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            title.setIcon(new DungeonMark()); title.setIconTextGap(12);
            connection = label("等待 AI 的公开回复", 12, new Color(178, 195, 202));
            gameState = label(lastGameState.isEmpty() ? "等待游戏状态" : lastGameState, 13, new Color(224, 204, 151));
            titles.add(title); titles.add(connection);
            head.add(titles, BorderLayout.CENTER);
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            controls.setOpaque(false);
            paused=check("手动接管",AiProbe.controlPaused(),true);
            paused.setToolTipText("暂停 AI 接口行动；你仍可在游戏中操作。");
            paused.addActionListener(e -> AiProbe.requestControlPaused(paused.isSelected()));
            controls.add(paused);
            JCheckBox pinned = check("窗口置顶", false,true);
            pinned.addActionListener(e -> window.setAlwaysOnTop(pinned.isSelected()));
            controls.add(pinned);
            JPanel lower = new JPanel(new BorderLayout(0,12));lower.setOpaque(false);
            JPanel stats = new RoundedPanel(new Color(53,70,79),12);
            stats.setLayout(new BorderLayout());stats.setBorder(new EmptyBorder(9,12,9,12));
            stats.add(gameState,BorderLayout.CENTER);
            lower.add(stats,BorderLayout.CENTER);lower.add(controls,BorderLayout.SOUTH);
            head.add(lower, BorderLayout.SOUTH);
            content.add(head, BorderLayout.NORTH);
            JPanel middle = new JPanel(new BorderLayout());
            middle.setBackground(PAPER);
            JPanel toolbar = new JPanel(new BorderLayout());
            toolbar.setOpaque(false); toolbar.setBorder(new EmptyBorder(12,20,8,20));
            toolbar.add(label("对话与回合记录",12,INK),BorderLayout.WEST);
            records = check("操作记录",true,false);
            records.addActionListener(e -> {renderedSequence=-1;render();});
            JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));
            tools.setOpaque(false);
            latest = button("最新消息",false);
            latest.setBorder(new EmptyBorder(5,10,5,10));latest.setVisible(false);
            latest.addActionListener(e -> {
                scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                unread=0; latest.setVisible(false);
            });
            tools.add(latest); tools.add(records);
            toolbar.add(tools,BorderLayout.EAST);
            middle.add(toolbar,BorderLayout.NORTH);
            transcript = new JEditorPane("text/html", "");
            transcript.setEditable(false); transcript.setBackground(PAPER);
            transcript.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,Boolean.TRUE);
            transcript.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            transcript.setBorder(new EmptyBorder(0,18,12,18));
            scroll = new JScrollPane(transcript);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            styleScroll(scroll);
            scroll.getVerticalScrollBar().setUnitIncrement(18);
            scroll.getVerticalScrollBar().addAdjustmentListener(e -> {
                JScrollBar bar=scroll.getVerticalScrollBar();
                if(bar.getValue()+bar.getVisibleAmount()>=bar.getMaximum()-5) {
                    unread=0; latest.setVisible(false);
                }
            });
            middle.add(scroll, BorderLayout.CENTER);
            content.add(middle,BorderLayout.CENTER);
            JPanel foot = new JPanel(new BorderLayout(0,10));
            foot.setBackground(Color.WHITE); foot.setBorder(new EmptyBorder(14,20,16,20));
            input = new JTextArea(3,20) {
                @Override protected void paintComponent(Graphics graphics) {
                    super.paintComponent(graphics);
                    if(getText().isEmpty()) {
                        Graphics2D g=(Graphics2D)graphics.create();
                        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        g.setColor(new Color(126,142,151));g.setFont(getFont());
                        g.drawString("输入给 AI 的指令…",12,25);g.dispose();
                    }
                }
            };
            input.setFont(new Font("Microsoft YaHei",Font.PLAIN,14));
            input.setLineWrap(true); input.setWrapStyleWord(true);
            input.setMargin(new Insets(10,10,10,10));
            input.setBackground(new Color(246,248,249));
            input.setForeground(INK);
            input.setToolTipText("输入给 AI 的指令；Enter 发送，Shift + Enter 换行。");
            JButton send = button("发送给 AI",true);
            Runnable submit = () -> {
                String text = input.getText().trim();
                if(text.length()>1800) {hint.setText("消息过长，请缩减至 1800 字符以内。");return;}
                if (!text.isEmpty()) { append("user", text); input.setText(""); hint.setText("已记录；等待接入的 AI 读取并回复。"); }
            };
            send.addActionListener(e -> submit.run());
            input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"send");
            input.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"),"insert-break");
            input.getActionMap().put("send",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){submit.run();}});
            JScrollPane composer = new JScrollPane(input);
            composer.setBorder(BorderFactory.createEmptyBorder());
            composer.setOpaque(false);composer.getViewport().setOpaque(false);input.setOpaque(false);
            composer.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            styleScroll(composer);
            JPanel inputSurface=new RoundedPanel(new Color(246,248,249),12);
            inputSurface.setLayout(new BorderLayout());inputSurface.setBorder(new EmptyBorder(2,2,2,2));
            inputSurface.add(composer,BorderLayout.CENTER);
            foot.add(inputSurface,BorderLayout.CENTER);
            JPanel footer = new JPanel(new BorderLayout(8,0)); footer.setOpaque(false);
            hint = label("Enter 发送 · Shift + Enter 换行",11,new Color(103,121,131));
            footer.add(hint,BorderLayout.CENTER); footer.add(send,BorderLayout.EAST);
            foot.add(footer,BorderLayout.SOUTH); content.add(foot, BorderLayout.SOUTH);
            window.setContentPane(content); window.setMinimumSize(new Dimension(430,520));
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            window.setSize(470, Math.min(800,screen.height-40));
            window.setLocation(screen.x + Math.max(0, screen.width-490), screen.y+20);
            window.setVisible(true);
            new javax.swing.Timer(2000, e -> {
                long age = lastAssistant == 0 ? -1 : (System.currentTimeMillis()-lastAssistant)/1000;
                connection.setText(age < 0 ? "等待 AI 的公开回复" : "最近公开回复 · "+(age<60?age+" 秒前":age/60+" 分钟前"));
                preview();
            }).start();
            render();
        });
    }

    private static JLabel label(String text,int size,Color color) {
        JLabel result=new JLabel(text);result.setFont(new Font("Microsoft YaHei",Font.PLAIN,size));result.setForeground(color);return result;
    }
    private static JCheckBox check(String text,boolean selected,boolean dark) {
        JCheckBox result=new JCheckBox(text,selected);result.setOpaque(false);result.setFocusPainted(false);
        result.setFont(new Font("Microsoft YaHei",Font.PLAIN,12));result.setForeground(dark?new Color(216,227,231):INK);
        result.setIcon(new CheckMark(false,dark));result.setSelectedIcon(new CheckMark(true,dark));result.setIconTextGap(7);
        result.setBorder(new EmptyBorder(0,0,0,18));return result;
    }
    private static final class RoundedPanel extends JPanel {
        private final Color fill;private final int radius;
        RoundedPanel(Color fill,int radius){this.fill=fill;this.radius=radius;setOpaque(false);}
        @Override protected void paintComponent(Graphics graphics){
            Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill);g.fillRoundRect(0,0,getWidth(),getHeight(),radius,radius);g.dispose();super.paintComponent(graphics);
        }
    }
    private static JButton button(String text,boolean primary) {
        JButton result=new JButton(text) {
            @Override protected void paintComponent(Graphics graphics){
                Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(primary?(getModel().isRollover()?new Color(56,79,92):INK):new Color(221,232,238));
                g.fillRoundRect(0,0,getWidth(),getHeight(),12,12);
                if(isFocusOwner()){g.setColor(new Color(170,194,206));g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,11,11);}
                g.dispose();super.paintComponent(graphics);
            }
        };
        result.setContentAreaFilled(false);result.setBorderPainted(false);result.setFocusPainted(false);
        result.setForeground(primary?Color.WHITE:INK);result.setFont(new Font("Microsoft YaHei",Font.BOLD,12));
        result.setBorder(new EmptyBorder(10,18,10,18));result.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));return result;
    }
    private static final class CheckMark implements Icon {
        private final boolean selected,dark;
        CheckMark(boolean selected,boolean dark){this.selected=selected;this.dark=dark;}
        public int getIconWidth(){return 16;}public int getIconHeight(){return 16;}
        public void paintIcon(Component c,Graphics graphics,int x,int y){
            Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(selected?(dark?new Color(224,204,151):new Color(70,104,124)):(dark?new Color(57,77,87):Color.WHITE));
            g.fillRoundRect(x,y,15,15,5,5);g.setColor(dark?new Color(135,156,167):new Color(169,188,198));g.drawRoundRect(x,y,15,15,5,5);
            if(selected){g.setColor(dark?INK:Color.WHITE);g.setStroke(new BasicStroke(1.8f));g.drawLine(x+4,y+8,x+7,y+11);g.drawLine(x+7,y+11,x+12,y+5);}
            if(c.isFocusOwner()){g.setColor(new Color(150,187,207));g.drawRoundRect(x-1,y-1,17,17,5,5);}g.dispose();
        }
    }
    private static final class DungeonMark implements Icon {
        public int getIconWidth(){return 34;}public int getIconHeight(){return 34;}
        public void paintIcon(Component c,Graphics graphics,int x,int y){
            Graphics2D g=(Graphics2D)graphics.create();g.setColor(new Color(224,204,151));
            int[][] stones={{0,0},{1,0},{2,0},{0,1},{2,1},{0,2},{1,2}};
            for(int[] tile:stones)g.fillRect(x+tile[0]*11,y+tile[1]*11,8,8);
            g.setColor(new Color(122,154,171));g.fillRect(x+11,y+11,8,8);g.dispose();
        }
    }
    private static void styleScroll(JScrollPane pane) {
        JScrollBar bar=pane.getVerticalScrollBar();bar.setPreferredSize(new Dimension(10,0));
        bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI(){
            protected JButton createDecreaseButton(int orientation){return emptyButton();}
            protected JButton createIncreaseButton(int orientation){return emptyButton();}
            private JButton emptyButton(){JButton b=new JButton();b.setPreferredSize(new Dimension(0,0));b.setMinimumSize(new Dimension(0,0));b.setMaximumSize(new Dimension(0,0));return b;}
            protected void paintTrack(Graphics g,JComponent c,Rectangle bounds){}
            protected void paintThumb(Graphics graphics,JComponent c,Rectangle bounds){
                Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(170,188,197));g.fillRoundRect(bounds.x+2,bounds.y+2,Math.max(3,bounds.width-4),Math.max(8,bounds.height-4),6,6);g.dispose();
            }
        });bar.setOpaque(false);
    }
    private static String escape(Object text) {
        return String.valueOf(text).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("\n","<br>");
    }

    public static synchronized Map<String,Object> append(String role, String text) {
        if (!Arrays.asList("user", "assistant", "action", "result", "system").contains(role))
            throw new IllegalArgumentException("invalid_message_role");
        if (text == null || text.trim().isEmpty() || text.length()>1800)
            throw new IllegalArgumentException("invalid_message_text");
        loadHistory();
        Map<String,Object> entry = new LinkedHashMap<>();
        entry.put("id", ++sequence); entry.put("role",role); entry.put("text",text);
        entry.put("time",LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        entry.put("date",java.time.LocalDate.now().toString());
        entry.put("profile",profile);entry.put("session",session);
        entries.add(entry); while(entries.size()>200) entries.remove(0);
        if(role.equals("assistant")) lastAssistant=System.currentTimeMillis();
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, AiProbe.stringify(entry)+System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { System.err.println("Dialogue log write failed: "+e.getMessage()); }
        SwingUtilities.invokeLater(AiConversation::render);
        return entry;
    }

    /**
     * Player (role=user) lines the probe has not handed to the AI yet.
     * This only peeks: the caller must invoke markPlayerMessagesRead() after the
     * observation has actually been sent, so the internal observations taken
     * inside /action and /query can never swallow a player message.
     */
    public static synchronized List<Object> unreadPlayerMessages() {
        loadHistory();
        List<Object> out = new ArrayList<>();
        for (Map<String,Object> e : entries) {
            if (!"user".equals(e.get("role"))) continue;
            Object id = e.get("id");
            if (!(id instanceof Long) || ((Long)id) <= playerReadUpTo) continue;
            Map<String,Object> view = new LinkedHashMap<>();
            view.put("id", id);
            view.put("text", e.get("text"));
            view.put("time", e.get("time"));
            out.add(view);
        }
        return out;
    }

    /** Marks every player message currently queued as delivered to the AI. */
    public static synchronized void markPlayerMessagesRead() {
        for (Map<String,Object> e : entries) {
            if (!"user".equals(e.get("role"))) continue;
            Object id = e.get("id");
            if (id instanceof Long && ((Long)id) > playerReadUpTo) playerReadUpTo = (Long)id;
        }
    }

    private static synchronized void loadHistory() {        if(historyLoaded)return;
        historyLoaded=true;
        if(!Files.exists(log))return;
        try(java.nio.channels.SeekableByteChannel channel=Files.newByteChannel(log)) {
            long offset=Math.max(0,channel.size()-2*1024*1024);
            channel.position(offset);
            try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(channel),StandardCharsets.UTF_8))) {
                if(offset>0)reader.readLine();
                String line;
                while((line=reader.readLine())!=null) {
                    if(line.length()>12000)continue;
                    try {
                        com.badlogic.gdx.utils.JsonValue value=new com.badlogic.gdx.utils.JsonReader().parse(line);
                        if(!profile.equals(value.getString("profile","")) || session.equals(value.getString("session","")))continue;
                        String role=value.getString("role",""),text=value.getString("text","");
                        if(!Arrays.asList("user","assistant","action","result","system").contains(role) || text.isEmpty() || text.length()>1800)continue;
                        Map<String,Object> entry=new LinkedHashMap<>();
                        entry.put("role",role);entry.put("text",text);entry.put("time",value.getString("time",""));entry.put("date",value.getString("date",""));
                        history.add(entry);while(history.size()>200)history.remove(0);
                    }catch(Exception invalidLine){ /* Preserve the log; skip incomplete or malformed lines. */ }
                }
            }
        }catch(Exception e){System.err.println("Dialogue history read failed: "+e.getMessage());}
    }

    public static synchronized Map<String,Object> snapshot(long after) {
        List<Object> messages = new ArrayList<>();
        for(Map<String,Object> e:entries) if((Long)e.get("id")>after) messages.add(new LinkedHashMap<>(e));
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("messages",messages); out.put("last_id",sequence);
        out.put("session",session);
        out.put("last_assistant_ms",lastAssistant); out.put("window_visible",window!=null&&window.isVisible());
        out.put("always_on_top",window!=null&&window.isAlwaysOnTop());
        out.put("auto_request_focus",window!=null&&window.isAutoRequestFocus());
        out.put("history_display_count",Math.min(history.size(),200-entries.size()));
        return out;
    }

    public static void gameStatus(String value) {
        if(value.equals(lastGameState)) return;
        lastGameState=value;
        SwingUtilities.invokeLater(() -> { if(gameState!=null)gameState.setText(value); });
    }
    public static void controlStatus(boolean value) {
        SwingUtilities.invokeLater(() -> {if(paused!=null) paused.setSelected(value);});
    }

    private static synchronized void render() {
        if(transcript==null) return;
        if(renderedSequence==sequence)return;
        long previousSequence=renderedSequence;
        renderedSequence=sequence;
        JScrollBar bar=scroll.getVerticalScrollBar();
        int oldPosition=bar.getValue();
        boolean follow=oldPosition+bar.getVisibleAmount()>=bar.getMaximum()-35;
        int added=previousSequence<0?0:(int)(sequence-previousSequence);
        StringBuilder text=new StringBuilder("<html><body style='font-family:Microsoft YaHei;font-size:11px;color:#273741;margin:0'>");
        if(entries.isEmpty()&&history.isEmpty()) text.append("<p style='color:#677983'>对话会显示在这里。<br><br>你可以发送指令，接入的 AI 会读取消息。<br>公开回复与实际行动记录会分别显示。</p>");
        List<Map<String,Object>> displayed=new ArrayList<>();
        int retained=Math.min(history.size(),200-entries.size());
        if(retained>0)displayed.addAll(history.subList(history.size()-retained,history.size()));
        displayed.addAll(entries);
        int position=0;
        for(Map<String,Object> e:displayed) {
            if(position==0 && retained>0)text.append("<p style='color:#677983;font-size:11px'>历史对话 · 仅供阅读，不代表当前游戏状态</p>");
            if(position==retained && retained>0)text.append("<p style='color:#677983;font-size:11px'>本次运行</p>");
            boolean historical=position++<retained;
            String role=(String)e.get("role");
            boolean event=role.equals("action")||role.equals("result")||role.equals("system");
            if(!records.isSelected()&&event)continue;
            String name=role.equals("assistant")?"AI · 公开回复":role.equals("user")?"你 · 玩家指令":role.equals("action")?"行动":role.equals("result")?"结果":"系统";
            String background=role.equals("user")?"#dfeaf0":event?"#e7edef":"#ffffff";
            text.append("<table width='100%' cellspacing='0' cellpadding='").append(event?8:13).append("' bgcolor='").append(background).append("'><tr><td>")
                .append("<span style='font-size:11px;color:#677983'>").append(name).append(" &nbsp; ").append(historical?escape(e.get("date"))+" ":"").append(escape(e.get("time"))).append("</span><br>")
                .append("<span style='font-size:").append(event?12:14).append("px'>").append(escape(e.get("text"))).append("</span></td></tr></table><p style='margin-top:6px;margin-bottom:0'></p>");
        }
        text.append("</body></html>");
        transcript.setText(text.toString());
        SwingUtilities.invokeLater(() -> {
            bar.setValue(follow?bar.getMaximum():oldPosition);
            if(!follow&&added>0) {
                unread+=added; latest.setText("新消息 ("+unread+")"); latest.setVisible(true);
            }
            preview();
        });
    }

    private static void preview() {
        if(window==null)return;
        // Render this application's own conversation component for repeatable visual QA.
        try {
            Container pane=window.getContentPane();
            if(pane.getWidth()>0 && pane.getHeight()>0) {
                java.awt.image.BufferedImage preview=new java.awt.image.BufferedImage(pane.getWidth(),pane.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                Graphics2D g=preview.createGraphics(); pane.printAll(g); g.dispose();
                Files.createDirectories(log.getParent());
                javax.imageio.ImageIO.write(preview,"png",log.resolveSibling("conversation-preview.png").toFile());
            }
        } catch(Exception e) { System.err.println("Conversation preview failed: "+e.getMessage()); }
    }
}
