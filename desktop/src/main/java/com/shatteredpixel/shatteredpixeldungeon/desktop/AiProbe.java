package com.shatteredpixel.shatteredpixeldungeon.desktop;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import com.badlogic.gdx.utils.JsonWriter;
import com.shatteredpixel.shatteredpixeldungeon.*;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.*;
import com.watabou.noosa.Game;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in desktop prototype. Exports visible observations, never full level objects. */
public final class AiProbe {
    private static long revision = 0;
    private static String lastState = "";
    private static boolean starting;
    private static boolean pendingResult;
    private static volatile boolean controlPaused;
    private static volatile boolean dialogueEnabled=Boolean.getBoolean("spd.ai.chat");
    private static final Map<String,Map<String,Object>> requestReceipts=new LinkedHashMap<>();
    private static boolean receiptsLoaded;
    private static java.nio.file.Path receiptsFile() {
        String profile=System.getProperty("Specification-Title","desktop");
        return java.nio.file.Paths.get("ai-probe","request-receipts",UUID.nameUUIDFromBytes(profile.getBytes(StandardCharsets.UTF_8))+".json");
    }
    private static Object receiptValue(JsonValue value) {
        if(value.isObject()){Map<String,Object> out=new LinkedHashMap<>();for(JsonValue child=value.child;child!=null;child=child.next)out.put(child.name,receiptValue(child));return out;}
        if(value.isArray()){List<Object> out=new ArrayList<>();for(JsonValue child=value.child;child!=null;child=child.next)out.add(receiptValue(child));return out;}
        if(value.isNull())return null;if(value.isBoolean())return value.asBoolean();if(value.isLong())return value.asLong();if(value.isDouble())return value.asDouble();return value.asString();
    }
    @SuppressWarnings("unchecked") private static void loadReceipts() throws java.io.IOException {
        if(receiptsLoaded)return;
        if(java.nio.file.Files.exists(receiptsFile())) {
            JsonValue root=new JsonReader().parse(java.nio.file.Files.readString(receiptsFile()));
            for(JsonValue child=root.child;child!=null;child=child.next)requestReceipts.put(child.name,(Map<String,Object>)receiptValue(child));
        }
        receiptsLoaded=true;
    }
    private static void saveReceipts() throws java.io.IOException {
        java.nio.file.Path path=receiptsFile(),temp=path.resolveSibling(path.getFileName()+".tmp");java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(temp,stringify(requestReceipts),StandardCharsets.UTF_8);
        try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(temp,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
        try{java.nio.file.Files.move(temp,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
        catch(java.nio.file.AtomicMoveNotSupportedException e){java.nio.file.Files.move(temp,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    }
    private static Object orderedReceiptValue(Object value) {
        if(value instanceof Map){Map<String,Object> out=new TreeMap<>();for(Object entryObject:((Map<?,?>)value).entrySet()){Map.Entry<?,?> entry=(Map.Entry<?,?>)entryObject;out.put(String.valueOf(entry.getKey()),orderedReceiptValue(entry.getValue()));}return out;}
        if(value instanceof List){List<Object> out=new ArrayList<>();for(Object child:(List<?>)value)out.add(orderedReceiptValue(child));return out;}
        return value;
    }
    private static Map<String,Object> trackedRequest(String endpoint,JsonValue input) throws java.io.IOException {
        String id=input.getString("request_id",null);
        if(id==null)return endpoint.equals("/action")?act(input):endpoint.equals("/start")?begin(input):load(input);
        if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("invalid_request_id");
        loadReceipts();String fingerprint=endpoint+":"+stringify(orderedReceiptValue(receiptValue(input)));
        Map<String,Object> existing=requestReceipts.get(id);
        if(existing!=null) {
            if(!fingerprint.equals(existing.get("fingerprint")))throw new IllegalArgumentException("request_id_conflict");
            return map("request_id",id,"duplicate",true,"receipt",existing);
        }
        Map<String,Object> receipt=map("request_id",id,"endpoint",endpoint,"fingerprint",fingerprint,"status","submitted","session",compactSession);
        requestReceipts.put(id,receipt);saveReceipts();
        Map<String,Object> result;
        try{result=endpoint.equals("/action")?act(input):endpoint.equals("/start")?begin(input):load(input);receipt.put("status","accepted");}
        catch(Exception e){
            String reason=String.valueOf(e.getMessage());
            // Any IllegalArgumentException raised by act()/begin()/load() is a pre-execution
            // validation failure (bad target, wrong action for that tile, stale observation...).
            // Classifying it as "unknown" instead of "rejected" leaves the client permanently
            // blocked, because an unknown request must never be resent and only a human resolve
            // can clear it. Keep the explicit list as a fallback but treat every IAE as rejected,
            // so newly added validation messages cannot poison the session again.
            boolean validation=(e instanceof IllegalArgumentException)
                    || Arrays.asList("control_paused","not_ready","stale_observation","return_to_title_before_starting","return_to_title_before_loading","invalid_hero_class","hero_class_locked","invalid_challenges","no_empty_save_slot","invalid_slot","missing_or_incompatible_save","save_from_newer_version","no_pickup_here","no_visible_target","target_not_visible","target_not_adjacent","invalid_direction","out_of_bounds","unsupported_or_blocked_terrain","visible_entity_use_attack","target_is_interactive","target_out_of_weapon_reach","item_not_in_inventory","unsupported_item_action","selection_pending","no_visible_container","no_interactive_target","stand_on_stairs_first","wrong_stair_direction","floor_locked","no_locked_door").contains(reason);
            result=map("error",reason);receipt.put("status",validation?"rejected":"unknown");
        }
        receipt.put("result",result);saveReceipts();
        Map<String,Object> out=new LinkedHashMap<>(result);out.put("request_id",id);out.put("request_status",receipt.get("status"));return out;
    }
    private static void knowledgeEntry(Map<String,Object> entries,String key,String kind,String name,String text) {
        if(text!=null && !text.isEmpty())entries.put(key,map("kind",kind,"name",name,"text",text));
    }
    private static Map<String,Object> visibleKnowledge(Map<String,Object> observation) {
        Map<String,Object> entries=new LinkedHashMap<>();
        if(!(Game.scene() instanceof GameScene) || Dungeon.level==null || !Boolean.TRUE.equals(observation.get("input_ready")))return entries;
        Level level=Dungeon.level;
        for(Mob mob:level.mobs) if(level.heroFOV[mob.pos] && mob.sprite!=null && mob.sprite.visible && mob.invisible<=0) {
            knowledgeEntry(entries,"entity:"+mob.name(),"entity",mob.name(),mob.info());
            for(Buff buff:mob.buffs())if(buff.icon()!=com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator.NONE)
                knowledgeEntry(entries,"effect:"+buff.name(),"effect",buff.name(),buff.desc());
        }
        for(int cell=0;cell<level.length();cell++)if(level.heroFOV[cell])
            knowledgeEntry(entries,"terrain:"+level.tileName(level.map[cell]),"terrain",level.tileName(level.map[cell]),level.tileDesc(level.map[cell]));
        for(com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap trap:level.traps.valueList())if(level.heroFOV[trap.pos] && trap.visible)
            knowledgeEntry(entries,"trap:"+trap.name(),"trap",trap.name(),trap.desc());
        for(com.shatteredpixel.shatteredpixeldungeon.plants.Plant plant:level.plants.valueList())if(level.heroFOV[plant.pos])
            knowledgeEntry(entries,"plant:"+plant.name(),"plant",plant.name(),plant.desc());
        for(com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob blob:level.blobs.values()) {
            if(blob.volume<=0 || blob.cur==null || blob.tileDesc()==null)continue;
            for(int cell=0;cell<level.length();cell++)if(level.heroFOV[cell] && blob.cur[cell]>0) {
                knowledgeEntry(entries,"field:"+blob.tileDesc(),"field",blob.tileDesc(),blob.tileDesc());break;
            }
        }
        for(int i=0;i<Challenges.MASKS.length;i++)if((Dungeon.challenges&Challenges.MASKS[i])!=0) {
            String id=Challenges.NAME_IDS[i];
            knowledgeEntry(entries,"challenge:"+id,"challenge",com.shatteredpixel.shatteredpixeldungeon.messages.Messages.get(Challenges.class,id),
                    com.shatteredpixel.shatteredpixeldungeon.messages.Messages.get(Challenges.class,id+"_desc"));
        }
        for(Buff buff:Dungeon.hero.buffs())if(buff.icon()!=com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator.NONE)
            knowledgeEntry(entries,"effect:"+buff.name(),"effect",buff.name(),buff.desc());
        for(Map<Talent,Integer> tier:Dungeon.hero.talents)
            for(Talent talent:tier.keySet())knowledgeEntry(entries,"talent:"+talent.name(),"talent",talent.title(),talent.desc());
        for(Item item:Dungeon.hero.belongings)
            knowledgeEntry(entries,"item:"+itemId(item),"item",item.title(),item.info());
        for(Heap heap:level.heaps.valueList())if(heap.seen && level.heroFOV[heap.pos] && (heap.type==Heap.Type.HEAP || heap.type==Heap.Type.FOR_SALE) && heap.peek()!=null)
            knowledgeEntry(entries,"ground:"+Dungeon.branch+":"+Dungeon.depth+":"+heap.pos,"ground_item",heap.peek().title(),heap.peek().info());
        return entries;
    }
    private static Map<String,Object> frame(long after,String mapAfter) {
        long actorBefore=Actor.actorLogicRevision();
        Map<String,Object> before=GLog.probeEvents(after);Map<String,Object> observation=observe();
        // Reuse this observation, never call observe again when constructing its map.
        Map<String,Object> terrain=compact(observation,mapAfter,true,false);
        Map<String,Object> knowledge=visibleKnowledge(observation);
        Map<String,Object> events=GLog.probeEvents(after);
        if(Game.scene() instanceof GameScene && Dungeon.level!=null)observation.put("branch",Dungeon.branch);
        events.put("session",compactSession);
        boolean consistent=actorBefore==Actor.actorLogicRevision() && Objects.equals(before.get("last_id"),events.get("last_id")) && Objects.equals(before.get("epoch"),events.get("epoch")) && !Game.switchingScene() && !Game.hasPendingRenderCallbacks();
        return map("decision_version",1,"observation",observation,"events",events,"consistent",consistent,"event_cutoff",events.get("last_id"),
                "map",terrain.containsKey("cursor")?terrain:null,"knowledge",knowledge);
    }
    private static void recordDialogue(String role,String text){if(dialogueEnabled)AiConversation.append(role,text);}
    public static boolean controlPaused(){return controlPaused;}
    public static void requestControlPaused(boolean paused) {
        if(Gdx.app!=null) Gdx.app.postRunnable(() -> setControlPaused(paused));
    }
    private static Map<String,Object> setControlPaused(boolean paused) {
        if(controlPaused!=paused) {
            controlPaused=paused; revision++;lastState="";
            if(dialogueEnabled)AiConversation.controlStatus(paused);
            recordDialogue("system",paused?"接口行动已暂停，可手动接管游戏。已提交的行动会按正常规则完成。":"接口行动已恢复。接入方必须重新观测后行动。");
        }
        return map("paused",controlPaused,"observation_id",revision);
    }
    private static Map<String,Object> capabilities() {
        Map<String,Object> actions=new LinkedHashMap<>();
        actions.put("move",Arrays.asList("dx","dy"));actions.put("attack",Arrays.asList("dx","dy"));
        for(String action:Arrays.asList("wait","search","pickup","descend","ascend","return_title","cancel_selection","alchemy_clear","alchemy_leave")) actions.put(action,Collections.emptyList());
        for(String action:Arrays.asList("unlock","open_container","interact","buy","alchemy","jump","attack_target","choose_cell","inspect_cell","inspect_entity")) actions.put(action,Arrays.asList("x","y"));
        actions.put("use_item",Arrays.asList("item_id","item_action","x/y when THROW"));
        for(String action:Arrays.asList("inspect_item","choose_item","alchemy_add")) actions.put(action,Arrays.asList("item_id"));
        for(String action:Arrays.asList("inspect_talent","upgrade_talent")) actions.put(action,Arrays.asList("talent_id"));
        for(String action:Arrays.asList("choose_option","choose_icon","choose_scene_option","alchemy_combine")) actions.put(action,Arrays.asList("index"));
        actions.put("choose_keep_slot",Arrays.asList("slot"));actions.put("continue",Collections.emptyList());
        actions.put("alchemy_select_slot",Arrays.asList("slot"));
        Map<String,Object> result=map("protocol_version",2,"base_url","http://127.0.0.1:18765",
                "endpoints",map("observe","GET /observe?format=compact&after=cursor","map","GET /map?after=cursor","journal","GET /journal?after=cursor","classes","GET /classes","saves","GET /saves","query","POST /query","action","POST /action","control","GET or POST /control","chat","GET or POST /chat","start","POST /start optional hero_class","load","POST /load","shutdown","POST /shutdown"),
                "actions",actions,"required_action_fields",Arrays.asList("type","observation_id except continue"),"optional_action_fields",dialogueEnabled?Arrays.asList("decision"):Collections.emptyList(),
                "event_endpoint","GET /events?after=message_id","compact_logs","Add logs=false to omit repeated message snapshots; read /events separately.",
                "queries",map("region",Arrays.asList("x","y","radius"),"find",Arrays.asList("terrain"),"route",Arrays.asList("x","y"),"frontier",Arrays.asList("limit optional, 1 to 32")),
                "rules",Arrays.asList("Read input_ready before submitting input.","Only ready=true permits new map actions; resolve selection first.","Use latest observation_id; never blindly retry an accepted action.","Decision and strategy are supplied by the external AI.","Pause rejects mutations; observations and inspections remain available.","Compact reset replaces cache; missing change fields remain unchanged; tiles merge by index."),
                "limits",Arrays.asList("Special scenes and non-text UI may require further adapters.","No autonomous model connection is included.","Route uses last seen terrain and cannot predict hidden hazards."));
        @SuppressWarnings("unchecked") Map<String,Object> endpoints=(Map<String,Object>)result.get("endpoints");
        endpoints.put("frame","GET /frame?after=event_id&map_after=acknowledged_map_cursor");endpoints.put("request","GET /request?id=request_id");
        result.put("decision_delivery",map("version",1,"frame","Stable observation, events, map delta and player-visible object descriptions in one read; client ACK commits all baselines","knowledge","First encounter and changed descriptions delivered automatically by formal client"));
        result.put("reliable_requests",map("request_id","Optional id for action/start/load; same normalized request is never executed twice","receipt","Persisted per desktop profile; accepted does not prove successful attack","unknown","Missing/submitted/unknown receipts do not authorize automatic retry","frame","Brackets observation with actor revision and event sequence; inconsistent frames must not drive actions"));
        if(!dialogueEnabled)endpoints.remove("chat");
        result.put("dialogue_enabled",dialogueEnabled);
        result.put("timing_rules",map("unit","1 game time unit = normal unmodified step; not a count of enemy actions",
                "preview","action_time_estimates and inventory action_time_estimates are successful-execution estimates; missing/null means context-dependent, never zero",
                "actual","Compare timing.spent_total before/after only when timing.clock_id matches; confirmation may defer spending; loading resets clock",
                "monster_state","visible_state is the drawn emotion icon; none does not imply idle or unaware",
                "health","health_bar is approximate player-visible bar percentage, not exact private HP/HT"));
        result.put("event_rules",map("retained",128,"kinds",Arrays.asList("log","visual"),
                "visual","Displayed floating text only. Damage is the displayed number, possibly including shield absorption, not hidden HP loss. surprise=true means the surprise icon was shown; absence does not prove no surprise. No hidden actor target or hit probability.",
                "cursor","Read /events after the last_id. Session/epoch changes reset the cursor; gap means retained events were lost."));
        result.put("start_options",map("hero_class","optional unlocked class","challenges","optional integer 0..511; omitted preserves selected challenges; 0 explicitly requests an unchallenged game"));
        return result;
    }
    private static Map<String,Object> journal(String after) {
        if(Game.switchingScene() || !(Game.scene() instanceof GameScene) || Dungeon.hero==null || Dungeon.level==null)
            throw new IllegalArgumentException("journal_unavailable");
        List<Object> records=new ArrayList<>();
        // Use exactly the public notebook records; never infer undiscovered landmarks or quest flags.
        for(com.shatteredpixel.shatteredpixeldungeon.journal.Notes.Record record:
                com.shatteredpixel.shatteredpixeldungeon.journal.Notes.getRecords(com.shatteredpixel.shatteredpixeldungeon.journal.Notes.Record.class)) {
            String kind=record instanceof com.shatteredpixel.shatteredpixeldungeon.journal.Notes.CustomRecord?"custom":
                    record instanceof com.shatteredpixel.shatteredpixeldungeon.journal.Notes.KeyRecord?"key":"landmark";
            records.add(map("kind",kind,"depth",record.depth(),"title",record.title(),"description",record.desc(),"quantity",record.quantity()));
        }
        String cursor=compactSession+":"+UUID.nameUUIDFromBytes((GamesInProgress.curSlot+":"+Dungeon.seed+":"+stringify(records)).getBytes(StandardCharsets.UTF_8));
        boolean changed=!cursor.equals(after);
        Map<String,Object> result=map("cursor",cursor,"changed",changed,"scope","player_notebook","branch",0);
        if(changed)result.put("records",records);
        return result;
    }
    private static final IdentityHashMap<Item,String> itemIds = new IdentityHashMap<>();
    private static int nextItemId;
    private static final Map<String,Map<Integer,String>> explored = new LinkedHashMap<>();
    private static final Map<String,List<String>> floorLegends=new HashMap<>();
    private static final LinkedHashMap<String,Map<String,Object>> compactHistory = new LinkedHashMap<>();
    private static long compactRevision;
    private static final String compactSession=UUID.randomUUID().toString().substring(0,8);
    private static final java.nio.file.Path MEMORY_FILE=java.nio.file.Paths.get("ai-probe","explored-map.json");
    static {
        try {
            if(java.nio.file.Files.exists(MEMORY_FILE)) {
                JsonValue root=new JsonReader().parse(java.nio.file.Files.readString(MEMORY_FILE));
                for(JsonValue floor=root.child;floor!=null;floor=floor.next) {
                    Map<Integer,String> cells=new LinkedHashMap<>();
                    for(JsonValue cell=floor.child;cell!=null;cell=cell.next) cells.put(Integer.parseInt(cell.name),cell.asString());
                    explored.put(floor.name,cells);
                }
            }
        } catch(Exception e){System.err.println("Map memory load: "+e.getMessage());}
    }

    private static String floorKey(){return System.getProperty("Specification-Title","desktop")+":"+GamesInProgress.curSlot+":"+Dungeon.seed+":"+Dungeon.branch+":"+Dungeon.depth;}
    private static void remember(Level level) {
        Map<Integer,String> memory=explored.computeIfAbsent(floorKey(),k->new LinkedHashMap<>());
        boolean changed=false;
        for(int i=0;i<level.length();i++) if(level.heroFOV[i]) {
            String name=level.tileName(level.map[i]);
            if(!name.equals(memory.put(i,name))) changed=true;
        }
        if(changed) try {
            Map<String,Object> data=new LinkedHashMap<>();
            for(Map.Entry<String,Map<Integer,String>> floor:explored.entrySet()) {
                Map<String,Object> cells=new LinkedHashMap<>();
                for(Map.Entry<Integer,String> cell:floor.getValue().entrySet()) cells.put(String.valueOf(cell.getKey()),cell.getValue());
                data.put(floor.getKey(),cells);
            }
            java.nio.file.Files.createDirectories(MEMORY_FILE.getParent());
            java.nio.file.Path temp=MEMORY_FILE.resolveSibling("explored-map.tmp");
            java.nio.file.Files.writeString(temp,stringify(data),StandardCharsets.UTF_8);
            java.nio.file.Files.move(temp,MEMORY_FILE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception e){System.err.println("Map memory save: "+e.getMessage());}
    }

    private static Map<String,Object> compact(String after, boolean mapOnly,boolean includeLogs) {
        return compact(observe(),after,mapOnly,includeLogs);
    }
    private static Map<String,Object> compact(Map<String,Object> observed,String after,boolean mapOnly,boolean includeLogs) {
        if(observed.containsKey("terminal")) return observed;
        if("AlchemyScene".equals(observed.get("scene"))) return observed;
        if(!Boolean.TRUE.equals(observed.get("input_ready"))) return observed;
        if(!observed.containsKey("hero")) return observed;
        Level level=Dungeon.level;
        List<String> stableLegend=floorLegends.computeIfAbsent(floorKey(),k->new ArrayList<>());
        for(String terrain:explored.get(floorKey()).values()) if(!stableLegend.contains(terrain)) stableLegend.add(terrain);
        List<String> legend=new ArrayList<>(stableLegend);
        Map<String,Object> tiles=new LinkedHashMap<>();
        for(Map.Entry<Integer,String> cell:explored.get(floorKey()).entrySet()) tiles.put(String.valueOf(cell.getKey()),legend.indexOf(cell.getValue()));
        Map<String,Object> state=new LinkedHashMap<>();
        if(!mapOnly) {
            for(String field:Arrays.asList("ready","input_ready","control_paused","scene","depth","run_info","hunger","hero","timing","action_time_estimates","selection","inventory","keys","effects","environment","entities","ground","map_items","talents","player_messages","messages")) state.put(field,observed.get(field));
            if(!includeLogs) state.remove("messages");
            state.put("includes_logs",includeLogs);
        }
        state.put("floor",Dungeon.branch+":"+Dungeon.depth);
        state.put("size",Arrays.asList(level.width(),level.height()));
        state.put("legend",legend); state.put("tiles",tiles);
        if(!mapOnly) {
            List<Integer> visible=new ArrayList<>();
            for(int i=0;i<level.length();i++) if(level.heroFOV[i]) visible.add(i);
            state.put("visible",visible);
            state.put("observation_id",revision);
        }
        String cursor=(mapOnly?"m":"o")+compactSession+"-"+(++compactRevision);
        Map<String,Object> previous=after!=null && after.startsWith(mapOnly?"m":"o") ? compactHistory.get(after) : null;
        boolean reset=previous==null || !Objects.equals(previous.get("floor"),state.get("floor")) || !Objects.equals(previous.get("includes_logs"),state.get("includes_logs"));
        Map<String,Object> changes=new LinkedHashMap<>();
        if(reset) changes.putAll(state);
        else for(Map.Entry<String,Object> field:state.entrySet()) {
            if(field.getKey().equals("tiles")) {
                Map<?,?> old=(Map<?,?>)previous.get("tiles");
                Map<String,Object> updates=new LinkedHashMap<>();
                for(Map.Entry<String,Object> cell:tiles.entrySet()) if(!Objects.equals(old.get(cell.getKey()),cell.getValue())) updates.put(cell.getKey(),cell.getValue());
                if(!updates.isEmpty()) changes.put("tiles",updates);
            } else if(!Objects.equals(stringify(field.getValue()),stringify(previous.get(field.getKey())))) changes.put(field.getKey(),field.getValue());
        }
        compactHistory.put(cursor,state);
        while(compactHistory.size()>64) compactHistory.remove(compactHistory.keySet().iterator().next());
        return map("cursor",cursor,"reset",reset,"changes",changes);
    }

    private static String itemId(Item item) {
        return itemIds.computeIfAbsent(item, i -> "item_"+(++nextItemId));
    }

    private static Map<String,Object> itemView(Item item, boolean detail) {
        Map<String,Object> result = map("id",itemId(item),"name",item.title(),
                "quantity",item.quantity(),"identified",item.isIdentified(),
                "equipped",item.isEquipped(Dungeon.hero),"actions",item.actions(Dungeon.hero),
                "supported_actions",supportedActions(item),"status",item.status());
        if(detail) result.put("description",item.info());
        Map<String,Object> costs=new LinkedHashMap<>();
        float scale=Dungeon.hero.actionTimeScale();
        for(String action:supportedActions(item)) {
            Float base=null;
            if(action.equals("DROP"))base=item.isEquipped(Dungeon.hero)?2f:1f;
            if(action.equals("EAT") && item instanceof com.shatteredpixel.shatteredpixeldungeon.items.food.Food)
                base=((com.shatteredpixel.shatteredpixeldungeon.items.food.Food)item).peekEatingTime();
            if(action.equals("DRINK") && item instanceof com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion)base=1f;
            if(action.equals("DRINK") && item instanceof com.shatteredpixel.shatteredpixeldungeon.items.Waterskin)base=1f;
            if(action.equals("READ") && item instanceof com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll)base=1f;
            if(action.equals("ZAP") && item instanceof com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand)base=1f;
            if(action.equals("USE") && item instanceof com.shatteredpixel.shatteredpixeldungeon.items.stones.InventoryStone)base=1f;
            if((action.equals("EQUIP")||action.equals("UNEQUIP")) && item instanceof com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem)
                base=((com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem)item).peekEquipTime(Dungeon.hero);
            costs.put(action,base==null?null:rounded(base/scale));
        }
        result.put("action_time_estimates",costs);
        return result;
    }

    private static double rounded(double value){return Math.round(value*1000d)/1000d;}
    private static Map<String,Object> actionTimeEstimates() {
        float scale=Dungeon.hero.actionTimeScale();
        Map<String,Object> estimates=new LinkedHashMap<>();
        estimates.put("move",rounded(1d/Dungeon.hero.speed()/scale));
        estimates.put("attack",rounded(Dungeon.hero.peekAttackDelay()/scale));
        estimates.put("attack_target",estimates.get("attack"));
        estimates.put("wait",1d);estimates.put("search",rounded(2d/scale));
        estimates.put("unlock",rounded(1d/scale));estimates.put("open_container",rounded(1d/scale));
        for(String action:Arrays.asList("alchemy","alchemy_leave","alchemy_add","alchemy_clear","alchemy_combine","return_title"))estimates.put(action,0d);
        Heap foot=Dungeon.level.heaps.get(Dungeon.hero.pos);
        if(foot!=null && foot.seen && foot.type==Heap.Type.HEAP && foot.peek()!=null)estimates.put("pickup",rounded(foot.peek().pickupDelay()/scale));
        for(String action:Arrays.asList("inspect_item","inspect_cell","inspect_entity","inspect_talent","upgrade_talent"))estimates.put(action,0d);
        return estimates;
    }
    private static Map<String,Object> timing() {
        return map("clock_id",Dungeon.hero.probeClockId,"spent_total",rounded(Dungeon.hero.probeScheduledTime()));
    }
    private static Map<String,Object> challengeInfo(int mask,boolean descriptions) {
        List<Object> active=new ArrayList<>();
        for(int i=0;i<Challenges.MASKS.length;i++)if((mask&Challenges.MASKS[i])!=0) {
            String id=Challenges.NAME_IDS[i];
            Map<String,Object> entry=map("id",id,"name",com.shatteredpixel.shatteredpixeldungeon.messages.Messages.get(Challenges.class,id));
            if(descriptions)entry.put("description",com.shatteredpixel.shatteredpixeldungeon.messages.Messages.get(Challenges.class,id+"_desc"));
            active.add(entry);
        }
        return map("mask",mask,"count",active.size(),"normal_difficulty",mask==0,"active",active);
    }
    private static Map<String,Object> hunger() {
        com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger buff=Dungeon.hero.buff(com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger.class);
        boolean wellFed=Dungeon.hero.buff(com.shatteredpixel.shatteredpixeldungeon.actors.buffs.WellFed.class)!=null;
        String stage=buff==null?"unknown":buff.isStarving()?"starving":
                buff.icon()==com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator.HUNGER?"hungry":wellFed?"well_fed":"not_hungry";
        Map<String,Object> out=map("stage",stage,"well_fed",wellFed,"source","player-visible hunger and well-fed icons","starvation_stops_natural_regeneration",true);
        if(buff!=null && buff.icon()!=com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator.NONE)out.put("description",buff.desc());
        if(stage.equals("starving"))out.put("natural_regeneration","blocked_by_starvation");
        else if(stage.equals("unknown"))out.put("natural_regeneration","unknown");
        else out.put("natural_regeneration","not_blocked_by_hunger; other visible effects or floor restrictions can still prevent healing");
        return out;
    }
    private static Map<String,Object> entityView(Mob mob,int width) {
        Map<String,Object> out=map("x",mob.pos%width,"y",mob.pos/width,"name",mob.name(),"interactive",mob.heroShouldInteract(),
                "visible_state",mob.sprite.probeVisibleEmotion());
        if(!com.shatteredpixel.shatteredpixeldungeon.actors.Char.hasProp(mob,com.shatteredpixel.shatteredpixeldungeon.actors.Char.Property.OBJECT)) {
            float hp=mob.HP,shield=mob.shielding(),max=Math.max(hp+shield,mob.HT);
            double health=max>0?Math.round(hp/max*20)*5:0;
            if(hp>0)health=Math.max(5,health);
            out.put("health_bar",map("hp_percent",health,"shield_percent",max>0?Math.round(shield/max*20)*5:0,
                    "damaged",mob.HP<mob.HT,"approximate",true,"precision_percent",5,"source","normal player inspection health bar"));
        }
        List<String> effects=new ArrayList<>();
        for(Buff buff:mob.buffs())if(buff.icon()!=com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator.NONE)effects.add(buff.name());
        if(!effects.isEmpty())out.put("effects",effects);
        return out;
    }

    private static List<String> supportedActions(Item item) {
        List<String> result = new ArrayList<>();
        for(String action : item.actions(Dungeon.hero)) {
            // Offer the actions the normal item menu currently offers. Follow-up UI is observed separately.
            result.add(action);
        }
        return result;
    }



    public static void start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18765), 0);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ai-probe-http"); t.setDaemon(true); return t;
            }));
            server.createContext("/", AiProbe::serve);
            server.start();
            if(dialogueEnabled) {
            AiConversation.open();
            recordDialogue("system", "对话窗口已启动。当前没有连接独立 AI；接口行动与公开回复将显示在这里。");
            java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t=new Thread(r,"ai-conversation-observer"); t.setDaemon(true); return t;
            }).scheduleAtFixedRate(() -> {
                if(Gdx.app==null || !queued.compareAndSet(false,true)) return;
                Gdx.app.postRunnable(() -> {
                    try { observe(); } catch(Exception e) { System.err.println("Conversation observation: "+e.getMessage()); }
                    finally { queued.set(false); }
                });
            },1,1,TimeUnit.SECONDS);
            }
            System.out.println("AI_PROBE http://127.0.0.1:18765");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start AI probe on port 18765", e);
        }
    }

    private static void serve(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        int status = 200;
        String response;
        try {
            ProbeHttpPolicy.validate(exchange.getRequestHeaders().getFirst("Host"),
                    exchange.getRequestHeaders().getFirst("Origin"));
            if(path.equals("/capabilities") && exchange.getRequestMethod().equals("GET")) {
                response=stringify(capabilities());exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            } else if (path.equals("/chat") || path.equals("/chat/window")) {
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                if (origin != null && !origin.equals("http://127.0.0.1:18765")) throw new IllegalArgumentException("foreign_origin");
                Map<String,Object> out;
                if (path.equals("/chat/window") && exchange.getRequestMethod().equals("POST")) {
                    dialogueEnabled=true; AiConversation.open(); out = map("accepted",true);
                } else if (path.equals("/chat") && exchange.getRequestMethod().equals("GET")) {
                    String query = exchange.getRequestURI().getRawQuery();
                    long after = query == null ? 0 : Long.parseLong(query.replaceFirst("^after=", ""));
                    out = AiConversation.snapshot(after);
                } else if (path.equals("/chat") && exchange.getRequestMethod().equals("POST")) {
                    byte[] body = exchange.getRequestBody().readNBytes(8193);
                    if (body.length>8192) throw new IllegalArgumentException("body_too_large");
                    JsonValue input = new JsonReader().parse(new String(body,StandardCharsets.UTF_8));
                    String role = input.getString("role", "");
                    if (!role.equals("user") && !role.equals("assistant")) throw new IllegalArgumentException("invalid_public_role");
                    out = AiConversation.append(role,input.getString("text", ""));
                } else throw new IllegalArgumentException("wrong_method");
                response = stringify(out);
                exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            } else if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
                response = PANEL;
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            } else {
                if (!path.equals("/frame") && !path.equals("/request") && !path.equals("/observe") && !path.equals("/events") && !path.equals("/map") && !path.equals("/journal") && !path.equals("/classes") && !path.equals("/saves") && !path.equals("/control") && !path.equals("/query") && !path.equals("/action") && !path.equals("/start") && !path.equals("/load") && !path.equals("/shutdown"))
                    throw new IllegalArgumentException("unknown_endpoint");
                String method = path.equals("/frame") || path.equals("/request") || path.equals("/observe") || path.equals("/events") || path.equals("/map") || path.equals("/journal") || path.equals("/classes") || path.equals("/saves") || path.equals("/control") && exchange.getRequestMethod().equals("GET") ? "GET" : "POST";
                if (!exchange.getRequestMethod().equals(method)) throw new IllegalArgumentException("wrong_method");
                // No browser cross-origin control of this local prototype.
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                if (origin != null && !origin.equals("http://127.0.0.1:18765"))
                    throw new IllegalArgumentException("foreign_origin");
                byte[] body = exchange.getRequestBody().readNBytes(4097);
                if (body.length > 4096) throw new IllegalArgumentException("body_too_large");
                String input = new String(body, StandardCharsets.UTF_8);
                CompletableFuture<String> future = new CompletableFuture<>();
                if (Gdx.app == null) throw new IllegalArgumentException("game_initializing");
                // Read/mutate on render thread only, and only export when the hero is ready for input.
                Gdx.app.postRunnable(() -> {
                    if (future.isCancelled()) return;
                    try {
                        Map<String,Object> out;
                        if(path.equals("/classes")) out=classes();
                        else if(path.equals("/saves")) out=saves();
                        else if(path.equals("/control")) {
                            out=method.equals("GET")?map("paused",controlPaused,"observation_id",revision):setControlPaused(new JsonReader().parse(input).getBoolean("paused"));
                        } else if (path.equals("/shutdown")) {
                            if (Game.scene() instanceof GameScene && Dungeon.hero != null && Dungeon.level != null && Dungeon.hero.isAlive() && Dungeon.hero.ready && !GameScene.interfaceBlockingHero()) Dungeon.saveAll();
                            out = map("accepted",true);
                            Gdx.app.postRunnable(() -> Gdx.app.exit());
                        } else if (path.equals("/start") || path.equals("/load") || path.equals("/action")) out = trackedRequest(path,new JsonReader().parse(input));
                        else if (path.equals("/query")) out = query(new JsonReader().parse(input));
                        else {
                            Map<String,String> query=new HashMap<>();
                            String raw=exchange.getRequestURI().getRawQuery();
                            if(raw!=null) for(String pair:raw.split("&")) {String[] parts=pair.split("=",2);query.put(parts[0],parts.length>1?parts[1]:"");}
                            if(path.equals("/frame"))out=frame(Long.parseLong(query.getOrDefault("after","0")),query.get("map_after"));
                            else if(path.equals("/request")){loadReceipts();out=requestReceipts.containsKey(query.get("id"))?map("found",true,"receipt",requestReceipts.get(query.get("id"))):map("found",false,"status","unknown");}
                            else if(path.equals("/journal")) out=journal(query.get("after"));
                            else if(path.equals("/events")) {
                                out=com.shatteredpixel.shatteredpixeldungeon.utils.GLog.probeEvents(Long.parseLong(query.getOrDefault("after","0")));
                                out.put("session",compactSession);
                            } else out=path.equals("/map") || "compact".equals(query.get("format")) ? compact(query.get("after"),path.equals("/map"),!"false".equals(query.get("logs"))) : observe();
                        }
                        future.complete(encode(out).toJson(JsonWriter.OutputType.json));
                    } catch (Exception e) { future.completeExceptionally(e); }
                });
                try { response = future.get(8, TimeUnit.SECONDS); }
                catch (TimeoutException e) { future.cancel(false); throw new IllegalArgumentException("game_not_responding"); }
                // Player lines are only consumed once a real observation has been
                // delivered, so internal observe() calls cannot swallow them.
                if (path.equals("/observe")) AiConversation.markPlayerMessagesRead();
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            }
        } catch (Exception e) {
            status = 400;
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            response = "{\"error\":\"" + String.valueOf(cause.getMessage()).replace("\\", "/").replace("\"", "'").replace("\n"," ") + "\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String,Object> load(JsonValue input) {
        if(controlPaused) throw new IllegalArgumentException("control_paused");
        if (!(Game.scene() instanceof TitleScene))
            throw new IllegalArgumentException("return_to_title_before_loading");
        int slot = input.getInt("slot", -1);
        if (slot < 1 || slot > GamesInProgress.MAX_SLOTS) throw new IllegalArgumentException("invalid_slot");
        GamesInProgress.Info info = GamesInProgress.check(slot);
        if (info == null) throw new IllegalArgumentException("missing_or_incompatible_save");
        if (info.version > Game.versionCode) throw new IllegalArgumentException("save_from_newer_version");
        GamesInProgress.curSlot = slot;
        compactHistory.clear();itemIds.clear();
        com.shatteredpixel.shatteredpixeldungeon.utils.GLog.clearProbeMessages();
        InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
        Game.switchScene(InterlevelScene.class);
        return map("accepted", true, "slot", slot, "message", "loading_saved_run");
    }
    private static Map<String,Object> classes() {
        List<Object> entries=new ArrayList<>();
        for(HeroClass heroClass:HeroClass.values()) {
            List<Object> subclasses=new ArrayList<>();
            for(com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass subclass:heroClass.subClasses()) subclasses.add(map("id",subclass.name(),"name",subclass.title(),"description",subclass.desc()));
            List<Object> abilities=new ArrayList<>();
            for(com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility ability:heroClass.armorAbilities()) abilities.add(map("name",ability.name(),"description",ability.desc()));
            entries.add(map("id",heroClass.name(),"name",heroClass.title(),"unlocked",heroClass.isUnlocked(),"description",heroClass.isUnlocked()?heroClass.desc():heroClass.unlockMsg(),"subclasses",subclasses,"armor_abilities",abilities));
        }
        return map("classes",entries,"default","WARRIOR","selected_challenges",challengeInfo(SPDSettings.challenges(),true));
    }
    private static int safeEmptySlot() {
        for(int slot=1;slot<=GamesInProgress.MAX_SLOTS;slot++) if(!GamesInProgress.gameExists(slot)) return slot;
        return -1;
    }
    private static Map<String,Object> saves() {
        List<Object> entries=new ArrayList<>();
        for(int slot=1;slot<=GamesInProgress.MAX_SLOTS;slot++) {
            GamesInProgress.Info info=GamesInProgress.check(slot);
            if(info==null) entries.add(map("slot",slot,"status",GamesInProgress.gameExists(slot)?"unreadable":"empty","loadable",false));
            else entries.add(map("slot",slot,"status","saved","loadable",info.version<=Game.versionCode,"depth",info.depth,"level",info.level,"hp",info.hp,"max_hp",info.ht,"class",info.heroClass.name(),"class_name",info.heroClass.title(),"subclass",info.subClass.name(),"source","saved_preview"));
        }
        return map("slots",entries,"current_slot",GamesInProgress.curSlot,"empty_slot",safeEmptySlot());
    }
    private static Map<String,Object> begin(JsonValue input) {
        if(controlPaused) throw new IllegalArgumentException("control_paused");
        if (!(Game.scene() instanceof TitleScene)) throw new IllegalArgumentException("return_to_title_before_starting");
        HeroClass selected;
        try{selected=HeroClass.valueOf(input.getString("hero_class","WARRIOR"));}catch(IllegalArgumentException e){throw new IllegalArgumentException("invalid_hero_class");}
        if(!selected.isUnlocked()) throw new IllegalArgumentException("hero_class_locked");
        JsonValue requestedChallenges=input.get("challenges");
        if(requestedChallenges!=null && (!requestedChallenges.isLong() || requestedChallenges.asLong()<0 || requestedChallenges.asLong()>Challenges.MAX_VALUE))throw new IllegalArgumentException("invalid_challenges");
        int challengeMask=requestedChallenges==null?SPDSettings.challenges():requestedChallenges.asInt();
        int emptySlot=safeEmptySlot();
        if (emptySlot < 1) throw new IllegalArgumentException("no_empty_save_slot");
        SPDSettings.challenges(challengeMask);
        GamesInProgress.curSlot=emptySlot;
        compactHistory.clear();itemIds.clear();
        com.shatteredpixel.shatteredpixeldungeon.utils.GLog.clearProbeMessages();
        SPDSettings.intro(false);
        GamesInProgress.selectedClass = selected;
        Dungeon.daily = Dungeon.dailyReplay = false;
        Dungeon.initSeed();
        // The normal new-game flow signals a fresh run by clearing the previous hero.
        Dungeon.hero=null;
        InterlevelScene.curTransition=null;
        InterlevelScene.mode = InterlevelScene.Mode.DESCEND;
        starting = true;
        Game.switchScene(InterlevelScene.class);
        return map("accepted", true,"hero_class",selected.name(),"challenges",challengeInfo(challengeMask,true), "message", "starting_new_run");
    }

    private static Map<String,Object> observe() {
        Map<String,Object> out=observeState();out.put("control_paused",controlPaused);return out;
    }
    private static Map<String,Object> observeState() {
        long actorVersion=Actor.actorLogicRevision();
        if(Game.switchingScene()) return map("ready",false,"input_ready",false,"scene",Game.scene()==null?"initializing":Game.scene().getClass().getSimpleName(),"reason","scene_change");
        if(Game.hasPendingRenderCallbacks()) return map("ready",false,"input_ready",false,"scene",Game.scene()==null?"initializing":Game.scene().getClass().getSimpleName(),"reason","ui_pending");
        if(Game.scene() instanceof GameScene && (actorVersion & 1L)!=0) return map("ready",false,"input_ready",false,"scene","GameScene","reason","processing");
        if(Game.scene() instanceof AmuletScene) {
            AmuletScene scene=(AmuletScene)Game.scene();
            Map<String,Object> out=map("ready",false,"input_ready",scene.probeReady(),"scene","AmuletScene","selection",scene.probeState());
            String value=stringify(out);if(!value.equals(lastState)){revision++;lastState=value;}out.put("observation_id",revision);return out;
        }
        if(Game.scene() instanceof RankingsScene || Game.scene() instanceof SurfaceScene) {
            Map<String,Object> out=map("ready",false,"input_ready",true,"scene",Game.scene().getClass().getSimpleName(),"allowed_actions",Arrays.asList("return_title"));
            String value=stringify(out);if(!value.equals(lastState)){revision++;lastState=value;}out.put("observation_id",revision);return out;
        }
        if(Game.scene() instanceof AlchemyScene) {
            List<Object> inventory=new ArrayList<>();for(Item item:Dungeon.hero.belongings) inventory.add(itemView(item,false));
            Map<String,Object> out=map("ready",false,"input_ready",true,"scene","AlchemyScene","alchemy",((AlchemyScene)Game.scene()).probeState(),"timing",timing(),"inventory",inventory,"selection",selectionView(currentWindow()));
            String state=stringify(out);if(!state.equals(lastState)){revision++;lastState=state;}
            out.put("observation_id",revision);return out;
        }
        if (Game.scene() instanceof InterlevelScene) return map("ready", false, "scene", "InterlevelScene", "story", ((InterlevelScene)Game.scene()).visibleStoryText(), "player_messages", AiConversation.unreadPlayerMessages());
        if (!(Game.scene() instanceof GameScene) || Dungeon.level == null || Dungeon.hero == null)
            return map("ready", false, "scene", Game.scene() == null ? "initializing" : Game.scene().getClass().getSimpleName(),"selected_challenges",challengeInfo(SPDSettings.challenges(),false), "player_messages", AiConversation.unreadPlayerMessages());
        if(com.shatteredpixel.shatteredpixeldungeon.levels.features.Chasm.jumpConfirmed)
            return map("ready",false,"scene","GameScene","reason","processing_jump");
        if (!Dungeon.hero.ready)
            if(Dungeon.hero.isAlive() && GameScene.probeWindow()==null) return map("ready", false, "scene", "GameScene", "reason", "processing");
        if(!Dungeon.hero.isAlive() && GameScene.probeWindow()==null) {
            Map<String,Object> dead=map("ready",false,"input_ready",true,"scene","GameScene","reason","dead","allowed_actions",Arrays.asList("return_title"),"player_messages",AiConversation.unreadPlayerMessages());
            dead.put("depth",Dungeon.depth);
            dead.put("hero",map("x",Dungeon.hero.pos%Dungeon.level.width(),"y",Dungeon.hero.pos/Dungeon.level.width(),"hp",Dungeon.hero.HP,"max_hp",Dungeon.hero.HT,"level",Dungeon.hero.lvl));
            dead.put("run_info",map("slot",GamesInProgress.curSlot,"challenges",challengeInfo(Dungeon.challenges,false)));
            dead.put("timing",timing());
            dead.put("terminal",map("outcome","dead","source","player-visible final state","messages",GLog.probeMessages()));
            String state=stringify(dead);if(!state.equals(lastState)){revision++;lastState=state;}dead.put("observation_id",revision);return dead;
        }
        Level level = Dungeon.level;
        remember(level);
        int w = level.width();
        List<Object> cells = new ArrayList<>();
        for (int i=0; i<level.length(); i++) {
            if (!level.heroFOV[i]) continue;
            // tileName deliberately presents secret doors as walls, secret traps as floor.
            cells.add(Arrays.asList(i%w, i/w, level.tileName(level.map[i])));
        }
        List<Object> enemies = new ArrayList<>();
        for (Mob mob : level.mobs) {
            if (level.heroFOV[mob.pos] && mob.sprite != null && mob.sprite.visible && mob.invisible <= 0) {
                // No exact enemy HP, internal AI state, IDs or drop contents.
                enemies.add(entityView(mob,w));
            }
        }
        List<Object> inventory = new ArrayList<>();
        for(Item item : Dungeon.hero.belongings) inventory.add(itemView(item,false));
        List<Object> keys=new ArrayList<>();
        for(com.shatteredpixel.shatteredpixeldungeon.items.keys.Key key:new com.shatteredpixel.shatteredpixeldungeon.items.keys.Key[]{
                new com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey(Dungeon.depth),
                new com.shatteredpixel.shatteredpixeldungeon.items.keys.GoldenKey(Dungeon.depth),
                new com.shatteredpixel.shatteredpixeldungeon.items.keys.CrystalKey(Dungeon.depth),
                new com.shatteredpixel.shatteredpixeldungeon.items.keys.WornKey(Dungeon.depth)}) {
            int count=com.shatteredpixel.shatteredpixeldungeon.journal.Notes.keyCount(key);
            if(count>0) keys.add(map("name",key.name(),"depth",Dungeon.depth,"quantity",count));
        }
        List<Object> effects = new ArrayList<>();
        for(Buff buff : Dungeon.hero.buffs()) {
            if(buff.icon()!=com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator.NONE)
                effects.add(map("name",buff.name(),"description",buff.desc()));
        }
        List<Object> ground = new ArrayList<>();
        List<Object> mapItems = new ArrayList<>();
        List<Object> environment=new ArrayList<>();
        for(com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap trap:level.traps.valueList())
            if(level.heroFOV[trap.pos] && trap.visible) environment.add(map("kind","trap","x",trap.pos%w,"y",trap.pos/w,"name",trap.name(),"active",trap.active));
        for(com.shatteredpixel.shatteredpixeldungeon.plants.Plant plant:level.plants.valueList())
            if(level.heroFOV[plant.pos]) environment.add(map("kind","plant","x",plant.pos%w,"y",plant.pos/w,"name",plant.name()));
        Map<String,List<Integer>> fields=new LinkedHashMap<>();
        for(com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob blob:level.blobs.values()) {
            String description=blob.tileDesc();
            if(blob.volume<=0 || blob.cur==null || description==null) continue;
            List<Integer> visible=fields.computeIfAbsent(description,k->new ArrayList<>());
            for(int i=0;i<level.length();i++) if(level.heroFOV[i] && blob.cur[i]>0 && !visible.contains(i)) visible.add(i);
        }
        for(Map.Entry<String,List<Integer>> field:fields.entrySet()) if(!field.getValue().isEmpty())
            environment.add(map("kind","field","description",field.getKey(),"cells",field.getValue()));
        for(Heap heap : level.heaps.valueList()) {
            // ItemSprite remains visible whenever heap.seen, including outside heroFOV.
            if(heap.seen) mapItems.add(map("x",heap.pos%w,"y",heap.pos/w,"name",heap.title(),
                    "visible",level.heroFOV[heap.pos],"pickup",heap.type==Heap.Type.HEAP,
                    "for_sale",heap.type==Heap.Type.FOR_SALE,
                    "container",heap.type!=Heap.Type.HEAP && heap.type!=Heap.Type.FOR_SALE));
            if(level.heroFOV[heap.pos] && heap.seen)
                ground.add(map("x",heap.pos%w,"y",heap.pos/w,"name",heap.title(),
                        "pickup",heap.type==Heap.Type.HEAP,"for_sale",heap.type==Heap.Type.FOR_SALE,"container",heap.type!=Heap.Type.HEAP && heap.type!=Heap.Type.FOR_SALE));
        }
        List<Object> talents = new ArrayList<>();
        for(int tier=1; tier<=Dungeon.hero.talents.size(); tier++) {
            List<Object> entries=new ArrayList<>();
            for(Map.Entry<Talent,Integer> entry : Dungeon.hero.talents.get(tier-1).entrySet())
                entries.add(map("id",entry.getKey().name(),"name",entry.getKey().title(),"points",entry.getValue(),"max_points",entry.getKey().maxPoints()));
            talents.add(map("tier",tier,"available_points",Dungeon.hero.talentPointsAvailable(tier),"entries",entries));
        }
        Window window=GameScene.probeWindow();
        String targetPrompt=GameScene.probeTargetPrompt();
        Map<String,Object> selection=null;
        if(window != null) {
            List<Object> options=new ArrayList<>();
            List<StyledButton> buttons=window.probeButtons();
            for(int i=0;i<buttons.size();i++) options.add(map("index",i,"label",buttons.get(i).text(),"enabled",buttons.get(i).active));
            selection=map("kind","window","text",window.probeText(),"options",options,"can_cancel",true);
            List<Object> icons=new ArrayList<>();
            List<com.shatteredpixel.shatteredpixeldungeon.ui.Button> iconButtons=window.probeIconButtons();
            for(int i=0;i<iconButtons.size();i++) {
                com.shatteredpixel.shatteredpixeldungeon.ui.Button button=iconButtons.get(i);
                icons.add(map("index",i,"label",button.probeLabel(),"enabled",button.active));
            }
            selection.put("icons",icons);
            if(window instanceof com.shatteredpixel.shatteredpixeldungeon.windows.WndResurrect) {
                selection.put("kind","resurrection");selection.put("kept_items",((com.shatteredpixel.shatteredpixeldungeon.windows.WndResurrect)window).probeKeptItems());selection.put("can_cancel",false);
            }
            if(window instanceof WndBag && ((WndBag)window).getSelector()!=null) {
                List<String> candidates=new ArrayList<>();
                for(Item item : Dungeon.hero.belongings) if(((WndBag)window).probeSelectable(item)) candidates.add(itemId(item));
                selection.put("kind","item"); selection.put("prompt",((WndBag)window).getSelector().textPrompt()); selection.put("item_ids",candidates);
            }
        } else if(targetPrompt!=null) selection=map("kind","cell","prompt",targetPrompt,"can_cancel",true);
        Map<String,Object> out = map("ready", !GameScene.interfaceBlockingHero() && targetPrompt==null,
                "input_ready",true,"selection",selection,"talents",talents,
                "scene", "GameScene", "depth", Dungeon.depth,"branch",Dungeon.branch,"run_info",map("slot",GamesInProgress.curSlot,"challenges",challengeInfo(Dungeon.challenges,false)),"hunger",hunger(),
                "hero", map("x", Dungeon.hero.pos%w, "y", Dungeon.hero.pos/w,
                        "hp", Dungeon.hero.HP, "max_hp", Dungeon.hero.HT,
                        "shield",Dungeon.hero.shielding(),"experience",Dungeon.hero.exp,"experience_to_level",Dungeon.hero.maxExp(),
                        "class",Dungeon.hero.heroClass.name(),"class_name",Dungeon.hero.heroClass.title(),
                        "subclass",Dungeon.hero.subClass.name(),"subclass_name",Dungeon.hero.subClass==com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass.NONE?null:Dungeon.hero.subClass.title(),
                        "armor_ability",Dungeon.hero.armorAbility==null?null:Dungeon.hero.armorAbility.name(),
                        "level",Dungeon.hero.lvl,"strength",Dungeon.hero.STR(),"gold",Dungeon.gold),
                "inventory",inventory,"timing",timing(),"action_time_estimates",actionTimeEstimates(),"keys",keys,"effects",effects,"environment",environment,"ground",ground,"map_items",mapItems,
                "player_messages",AiConversation.unreadPlayerMessages(),
                "messages", com.shatteredpixel.shatteredpixeldungeon.utils.GLog.probeMessages(), "cells", cells, "entities", enemies, "cell_format", Arrays.asList("x","y","terrain"));
        if(!Dungeon.hero.isAlive())out.put("terminal",map("outcome","awaiting_resurrection_or_dead","source","player-visible final state","messages",GLog.probeMessages()));
        // Actor logic runs on a separate thread. Do not publish a snapshot spanning a state change.
        if(Actor.actorLogicRevision()!=actorVersion || Game.switchingScene() || Game.hasPendingRenderCallbacks())
            return map("ready",false,"input_ready",false,"scene","GameScene","reason","processing");
        String state = encode(out).toJson(JsonWriter.OutputType.json);
        if (!state.equals(lastState)) { revision++; lastState = state; }
        if(dialogueEnabled)AiConversation.gameStatus("第 "+Dungeon.depth+" 层 · 生命 "+Dungeon.hero.HP+"/"+Dungeon.hero.HT+" · "+(Boolean.TRUE.equals(out.get("ready"))?"可操作":"等待菜单"));
        if (pendingResult) {
            pendingResult=false;
            recordDialogue("result", "位置 ("+(Dungeon.hero.pos%w)+","+(Dungeon.hero.pos/w)+")，生命 "+Dungeon.hero.HP+"/"+Dungeon.hero.HT+"，当前可见生物 "+enemies.size()+" 个。");
        }
        out.put("observation_id", revision);
        return out;
    }

    private static Map<String,Object> act(JsonValue input) {
        String requested=input.getString("type","");
        if(controlPaused && !Arrays.asList("inspect_item","inspect_talent","inspect_cell","inspect_entity").contains(requested)) throw new IllegalArgumentException("control_paused");
        if (input.getString("type", "").equals("continue") && Game.scene() instanceof InterlevelScene) {
            if (!((InterlevelScene)Game.scene()).continueVisibleStory()) throw new IllegalArgumentException("continue_not_available");
            return map("accepted", true, "action", "continue");
        }
        Map<String,Object> state = observe();
        if (!Boolean.TRUE.equals(state.get("input_ready"))) throw new IllegalArgumentException("not_ready");
        if (input.getLong("observation_id", -1) != revision) throw new IllegalArgumentException("stale_observation");
        String kind = input.getString("type", "");
        if((Game.scene() instanceof RankingsScene || Game.scene() instanceof SurfaceScene) && !kind.equals("return_title")) throw new IllegalArgumentException("unsupported_result_scene_action");
        if(Game.scene() instanceof AmuletScene && !kind.equals("choose_scene_option")) throw new IllegalArgumentException("resolve_ending_scene_first");
        if(kind.equals("choose_scene_option")) {
            if(!(Game.scene() instanceof AmuletScene)) throw new IllegalArgumentException("unsupported_scene_choice");
            ((AmuletScene)Game.scene()).probeChoose(input.getInt("index",-1));revision++;lastState="";
            recordDialogue("action",kind);return map("accepted",true,"action",kind);
        }
        if(kind.equals("inspect_entity")) {
            int cell=visibleCell(input);
            for(Mob mob:Dungeon.level.mobs) if(mob.pos==cell && mob.sprite!=null && mob.sprite.visible && mob.invisible<=0) {
                List<Object> buffs=new ArrayList<>();
                for(Buff buff:mob.buffs()) if(buff.icon()!=com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator.NONE) buffs.add(map("name",buff.name(),"description",buff.desc()));
                Map<String,Object> details=entityView(mob,Dungeon.level.width());details.put("description",mob.info());details.put("effects",buffs);
                details.put("consumes_turn",false);details.put("observation_id",revision);return details;
            }
            throw new IllegalArgumentException("no_visible_target");
        }
        if(kind.equals("inspect_cell")) {
            int cell=visibleCell(input);Level level=Dungeon.level;
            List<Object> details=new ArrayList<>();
            com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap trap=level.traps.get(cell);
            if(trap!=null && trap.visible) details.add(map("kind","trap","name",trap.name(),"description",trap.desc()));
            com.shatteredpixel.shatteredpixeldungeon.plants.Plant plant=level.plants.get(cell);
            if(plant!=null) details.add(map("kind","plant","name",plant.name(),"description",plant.desc()));
            for(com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob blob:level.blobs.values())
                if(blob.volume>0 && blob.cur!=null && blob.cur[cell]>0 && blob.tileDesc()!=null) details.add(map("kind","field","description",blob.tileDesc()));
            return map("terrain",level.tileName(level.map[cell]),"description",level.tileDesc(level.map[cell]),"details",details,"consumes_turn",false,"observation_id",revision);
        }
        if(kind.equals("return_title")) {
            if(!(Game.scene() instanceof RankingsScene || Game.scene() instanceof SurfaceScene) && Dungeon.hero!=null && Dungeon.hero.isAlive()) {
                if(!Boolean.TRUE.equals(state.get("ready"))) throw new IllegalArgumentException("finish_selection_before_leaving");
                try{Dungeon.saveAll();}catch(java.io.IOException e){throw new IllegalStateException("save_failed",e);}
            }
            Game.switchScene(TitleScene.class);starting=false;revision++;lastState="";pendingResult=false;
            recordDialogue("action","返回标题页");return map("accepted",true,"action",kind);
        }
        if("dead".equals(state.get("reason"))) throw new IllegalArgumentException("run_ended_return_to_title");
        if(kind.equals("inspect_talent")) {
            String id=input.getString("talent_id","");
            for(Map<Talent,Integer> tier : Dungeon.hero.talents) for(Talent talent : tier.keySet())
                if(talent.name().equals(id)) return map("id",id,"name",talent.title(),"description",talent.desc(),"consumes_turn",false,"observation_id",revision);
            throw new IllegalArgumentException("talent_not_available");
        }
        if(kind.equals("inspect_item")) {
            for(Item item : Dungeon.hero.belongings)
                if(itemId(item).equals(input.getString("item_id",""))) return map("item",itemView(item,true),"observation_id",revision,"consumes_turn",false);
            throw new IllegalArgumentException("item_not_in_inventory");
        }
        String decision = input.getString("decision", "").trim();
        if(decision.length()>1800) throw new IllegalArgumentException("decision_too_long");
        if(Arrays.asList("choose_item","choose_cell","choose_option","choose_icon","cancel_selection","choose_keep_slot").contains(kind)) {
            Window window=currentWindow();
            if(kind.equals("choose_icon")) {
                if(window==null) throw new IllegalArgumentException("no_window");
                int index=input.getInt("index",-1);
                if(index<0 || index>=window.probeIconButtons().size() || !window.probeIconButtons().get(index).active) throw new IllegalArgumentException("invalid_or_disabled_icon");
                if(!decision.isEmpty()) recordDialogue("assistant",decision);
                window.probeChooseIcon(index);
            } else if(kind.equals("choose_keep_slot")) {
                if(!(window instanceof com.shatteredpixel.shatteredpixeldungeon.windows.WndResurrect)) throw new IllegalArgumentException("no_resurrection_window");
                ((com.shatteredpixel.shatteredpixeldungeon.windows.WndResurrect)window).probeChooseSlot(input.getInt("slot",-1));
            } else if(kind.equals("choose_item")) {
                if(!(window instanceof WndBag)) throw new IllegalArgumentException("no_item_selection");
                Item chosen=null;
                for(Item item : Dungeon.hero.belongings) if(itemId(item).equals(input.getString("item_id",""))) chosen=item;
                if(chosen==null || !((WndBag)window).probeSelectable(chosen)) throw new IllegalArgumentException("item_not_selectable");
                if(!decision.isEmpty()) recordDialogue("assistant",decision);
                ((WndBag)window).probeSelect(chosen);
            } else if(kind.equals("choose_cell")) {
                if(window!=null || GameScene.probeTargetPrompt()==null) throw new IllegalArgumentException("no_cell_selection");
                int cell=visibleCell(input);
                if(!decision.isEmpty()) recordDialogue("assistant",decision);
                GameScene.handleCell(cell);
            } else if(kind.equals("choose_option")) {
                if(window==null) throw new IllegalArgumentException("no_window");
                int index=input.getInt("index",-1);
                List<StyledButton> buttons=window.probeButtons();
                if(index<0 || index>=buttons.size() || !buttons.get(index).active) throw new IllegalArgumentException("invalid_or_disabled_option");
                if(!decision.isEmpty()) recordDialogue("assistant",decision);
                window.probeChoose(index);
            } else {
                if(window==null && GameScene.probeTargetPrompt()==null) throw new IllegalArgumentException("no_selection");
                if(!decision.isEmpty()) recordDialogue("assistant",decision);
                if(window!=null) window.onBackPressed(); else GameScene.cancelCellSelector();
            }
            revision++; lastState=""; pendingResult=true;
            recordDialogue("action",kind);
            return map("accepted",true,"action",kind);
        }
        if(Game.scene() instanceof AlchemyScene) {
            if(currentWindow()!=null) throw new IllegalArgumentException("selection_pending");
            AlchemyScene scene=(AlchemyScene)Game.scene();
            if(kind.equals("alchemy_add")) {
                Item chosen=null;for(Item item:Dungeon.hero.belongings) if(itemId(item).equals(input.getString("item_id",""))) chosen=item;
                if(chosen==null) throw new IllegalArgumentException("item_not_in_inventory");scene.probeAdd(chosen);
            } else if(kind.equals("alchemy_clear")) scene.probeClear();
            else if(kind.equals("alchemy_select_slot")) scene.probeSelectSlot(input.getInt("slot",-1));
            else if(kind.equals("alchemy_combine")) scene.probeCombine(input.getInt("index",-1));
            else if(kind.equals("alchemy_leave")) scene.probeLeave();
            else throw new IllegalArgumentException("unsupported_alchemy_action");
            revision++;lastState="";recordDialogue("action",kind);return map("accepted",true,"action",kind);
        }
        if(!Boolean.TRUE.equals(state.get("ready"))) throw new IllegalArgumentException("selection_pending");
        if(kind.equals("attack_target")) {
            int cell=visibleCell(input);Mob target=null;
            for(Mob mob:Dungeon.level.mobs) if(mob.pos==cell && mob.sprite!=null && mob.sprite.visible && mob.invisible<=0) target=mob;
            if(target==null) throw new IllegalArgumentException("no_visible_target");
            if(target.heroShouldInteract()) throw new IllegalArgumentException("target_is_interactive");
            if(!Dungeon.hero.canAttack(target)) throw new IllegalArgumentException("target_out_of_weapon_reach");
            if(!decision.isEmpty()) recordDialogue("assistant",decision);
            Dungeon.hero.curAction=new HeroAction.Attack(target);Dungeon.hero.busy();Dungeon.hero.next();
            revision++;lastState="";pendingResult=true;recordDialogue("action","攻击可见目标 ("+input.getInt("x")+","+input.getInt("y")+")");
            return map("accepted",true,"action",kind);
        }
        if(Arrays.asList("search","open_container","unlock","interact","buy","alchemy","jump").contains(kind)) {
            Level level=Dungeon.level;
            if(kind.equals("search")) {
                if(!decision.isEmpty()) recordDialogue("assistant",decision);
                Dungeon.hero.search(true);
            } else {
                int cell=visibleCell(input);
                if(!level.adjacent(Dungeon.hero.pos,cell) && cell!=Dungeon.hero.pos) throw new IllegalArgumentException("target_not_adjacent");
                HeroAction action;
                if(kind.equals("jump")) {
                    if(cell==Dungeon.hero.pos || level.map[cell]!=Terrain.CHASM) throw new IllegalArgumentException("no_adjacent_chasm");
                    action=new HeroAction.Move(cell);
                } else if(kind.equals("alchemy")) {
                    if(level.map[cell]!=Terrain.ALCHEMY) throw new IllegalArgumentException("no_alchemy_station");
                    action=new HeroAction.Alchemy(cell);
                } else if(kind.equals("buy")) {
                    Heap heap=level.heaps.get(cell);
                    if(cell!=Dungeon.hero.pos) throw new IllegalArgumentException("stand_on_sale_item_first");
                    if(heap==null || !heap.seen || heap.type!=Heap.Type.FOR_SALE) throw new IllegalArgumentException("no_sale_item_here");
                    action=new HeroAction.Buy(cell);
                } else if(kind.equals("open_container")) {
                    Heap heap=level.heaps.get(cell);
                    if(heap==null || !heap.seen || heap.type==Heap.Type.HEAP || heap.type==Heap.Type.FOR_SALE) throw new IllegalArgumentException("no_visible_container");
                    action=new HeroAction.OpenChest(cell);
                } else if(kind.equals("unlock")) {
                    int terrain=level.map[cell];
                    if(cell==Dungeon.hero.pos || !(terrain==Terrain.LOCKED_DOOR || terrain==Terrain.CRYSTAL_DOOR || terrain==Terrain.LOCKED_EXIT || terrain==Terrain.HERO_LKD_DR)) throw new IllegalArgumentException("no_locked_door");
                    action=new HeroAction.Unlock(cell);
                } else {
                    Mob target=null;
                    for(Mob mob:level.mobs) if(mob.pos==cell && mob.sprite!=null && mob.sprite.visible && mob.invisible<=0 && mob.heroShouldInteract()) target=mob;
                    if(target==null) throw new IllegalArgumentException("no_interactive_target");
                    action=new HeroAction.Interact(target);
                }
                if(!decision.isEmpty()) recordDialogue("assistant",decision);
                Dungeon.hero.curAction=action;Dungeon.hero.busy();Dungeon.hero.next();
            }
            revision++;lastState="";pendingResult=true;recordDialogue("action",kind);
            return map("accepted",true,"action",kind,"message","poll_observe_until_input_ready");
        }
        if(kind.equals("descend") || kind.equals("ascend")) {
            Level level=Dungeon.level;
            com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition transition=level.getTransition(Dungeon.hero.pos);
            if(transition==null || !transition.inside(Dungeon.hero.pos)) throw new IllegalArgumentException("stand_on_stairs_first");
            boolean down=transition.type==com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition.Type.REGULAR_EXIT || transition.type==com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition.Type.BRANCH_EXIT;
            if(down!=kind.equals("descend")) throw new IllegalArgumentException("wrong_stair_direction");
            if(level.locked) throw new IllegalArgumentException("floor_locked");
            if(!decision.isEmpty()) recordDialogue("assistant",decision);
            Dungeon.hero.curAction=new HeroAction.LvlTransition(Dungeon.hero.pos);
            Dungeon.hero.busy(); Dungeon.hero.next();
            revision++;lastState="";pendingResult=true;
            recordDialogue("action",kind);
            return map("accepted",true,"action",kind);
        }
        if(kind.equals("upgrade_talent")) {
            String id=input.getString("talent_id","");
            Talent chosen=null; int tier=0;
            for(int i=0;i<Dungeon.hero.talents.size();i++) for(Talent talent : Dungeon.hero.talents.get(i).keySet())
                if(talent.name().equals(id)){chosen=talent;tier=i+1;}
            if(chosen==null) throw new IllegalArgumentException("talent_not_available");
            if(Dungeon.hero.talentPointsAvailable(tier)<=0) throw new IllegalArgumentException("no_talent_points");
            if(Dungeon.hero.pointsInTalent(chosen)>=chosen.maxPoints()) throw new IllegalArgumentException("talent_at_maximum");
            if(!decision.isEmpty()) recordDialogue("assistant",decision);
            Dungeon.hero.upgradeTalent(chosen);
            revision++; lastState="";
            recordDialogue("action","天赋加点："+chosen.title());
            return map("accepted",true,"action",kind,"consumes_turn",false);
        }
        if(kind.equals("use_item")) {
            Item chosen=null;
            for(Item item : Dungeon.hero.belongings)
                if(itemId(item).equals(input.getString("item_id",""))) chosen=item;
            if(chosen==null) throw new IllegalArgumentException("item_not_in_inventory");
            String action=input.getString("item_action","");
            if(!supportedActions(chosen).contains(action)) throw new IllegalArgumentException("unsupported_item_action");
            if(action.equals("THROW")) {
                int cell=visibleCell(input);
                if(!decision.isEmpty()) recordDialogue("assistant",decision);
                // Use the normal throw action so potion warnings and other confirmations are preserved.
                chosen.execute(Dungeon.hero,action);
                if(GameScene.probeWindow()==null && GameScene.probeTargetPrompt()!=null) GameScene.handleCell(cell);
            } else {
                if(!decision.isEmpty()) recordDialogue("assistant",decision);
                chosen.execute(Dungeon.hero,action);
            }
            revision++; lastState=""; pendingResult=true;
            recordDialogue("action",chosen.title()+"："+action);
            return map("accepted",true,"action",kind,"message","poll_observe_until_ready");
        }
        if(kind.equals("pickup")) {
            Heap heap=Dungeon.level.heaps.get(Dungeon.hero.pos);
            if(heap==null || heap.type!=Heap.Type.HEAP || !heap.seen) throw new IllegalArgumentException("no_pickup_here");
            if(!decision.isEmpty()) recordDialogue("assistant",decision);
            Dungeon.hero.curAction=new HeroAction.PickUp(Dungeon.hero.pos);
            Dungeon.hero.busy(); Dungeon.hero.next();
            revision++; lastState=""; pendingResult=true;
            recordDialogue("action","拾取脚下物品");
            return map("accepted",true,"action",kind);
        }
        if(!Arrays.asList("move","attack","wait").contains(kind)) throw new IllegalArgumentException("unsupported_action");
        if (kind.equals("wait")) {
            if(!decision.isEmpty()) recordDialogue("assistant",decision);
            Dungeon.hero.busy();
            Dungeon.hero.rest(false);
        } else {
            if (!kind.equals("move") && !kind.equals("attack")) throw new IllegalArgumentException("unsupported_action");
            int dx = input.getInt("dx", 0), dy = input.getInt("dy", 0);
            if (Math.abs(dx)>1 || Math.abs(dy)>1 || (dx==0 && dy==0)) throw new IllegalArgumentException("invalid_direction");
            Level level = Dungeon.level;
            int w = level.width(), x = Dungeon.hero.pos%w+dx, y = Dungeon.hero.pos/w+dy;
            if (x<0 || x>=w || y<0 || y>=level.height()) throw new IllegalArgumentException("out_of_bounds");
            int cell = x+y*w;
            if (!level.heroFOV[cell]) throw new IllegalArgumentException("target_not_visible");
            if (kind.equals("attack")) {
                Mob target = null;
                for (Mob mob : level.mobs)
                    if (mob.pos==cell && mob.sprite != null && mob.sprite.visible && mob.invisible<=0) target=mob;
                if (target==null) throw new IllegalArgumentException("no_visible_target");
                if (target.heroShouldInteract()) throw new IllegalArgumentException("target_is_interactive");
                Dungeon.hero.curAction = new HeroAction.Attack(target);
            } else {
                int terrain = level.map[cell];
                // Deliberately narrow: no transitions, chasms, locked doors, shops or containers.
                // PEDESTAL is PASSABLE in Terrain.java and is where opened crystal chests drop
                // their item, so it must be walkable here too or that loot can never be taken.
                if (!(terrain==Terrain.EMPTY || terrain==Terrain.EMPTY_DECO || terrain==Terrain.EMPTY_SP ||
                      terrain==Terrain.GRASS || terrain==Terrain.HIGH_GRASS || terrain==Terrain.FURROWED_GRASS ||
                      terrain==Terrain.WATER || terrain==Terrain.EMBERS || terrain==Terrain.OPEN_DOOR ||
                      terrain==Terrain.DOOR || terrain==Terrain.SECRET_TRAP || terrain==Terrain.TRAP ||
                      terrain==Terrain.ENTRANCE || terrain==Terrain.ENTRANCE_SP || terrain==Terrain.EXIT || terrain==Terrain.UNLOCKED_EXIT ||
                      terrain==Terrain.PEDESTAL ||
                      terrain==Terrain.WELL || // probe fix: wells are AVOID-but-enterable; hero must step on the tile to drink
                      terrain==Terrain.INACTIVE_TRAP))
                    throw new IllegalArgumentException("unsupported_or_blocked_terrain");
                for (Mob mob : level.mobs)
                    if (mob.pos==cell && mob.sprite!=null && mob.sprite.visible && mob.invisible<=0)
                        throw new IllegalArgumentException("visible_entity_use_attack");
                Dungeon.hero.curAction = new HeroAction.Move(cell);
            }
            if(!decision.isEmpty()) recordDialogue("assistant",decision);
            Dungeon.hero.busy();
            Dungeon.hero.next();
        }
        // Consume the observation even if resulting visible state remains identical.
        revision++; lastState = "";
        pendingResult=true;
        recordDialogue("action", kind.equals("wait") ? "等待一次" : kind+"，方向 ("+input.getInt("dx",0)+","+input.getInt("dy",0)+")");
        return map("accepted", true, "action", kind, "message", "poll_observe_until_ready");
    }

    public static String stringify(Object value) { return encode(value).toJson(JsonWriter.OutputType.json); }
    private static Window currentWindow() {
        Window result=null;
        if(Game.scene()!=null) for(com.watabou.noosa.Gizmo g:Game.scene().probeChildren()) if(g instanceof Window && g.visible) result=(Window)g;
        return result;
    }
    private static Map<String,Object> selectionView(Window window) {
        if(window==null) return null;
        List<Object> options=new ArrayList<>(),icons=new ArrayList<>();
        List<StyledButton> buttons=window.probeButtons();
        for(int i=0;i<buttons.size();i++) options.add(map("index",i,"label",buttons.get(i).text(),"enabled",buttons.get(i).active));
        List<com.shatteredpixel.shatteredpixeldungeon.ui.Button> iconButtons=window.probeIconButtons();
        for(int i=0;i<iconButtons.size();i++) icons.add(map("index",i,"label",iconButtons.get(i).probeLabel(),"enabled",iconButtons.get(i).active));
        Map<String,Object> result=map("kind","window","text",window.probeText(),"options",options,"icons",icons,"can_cancel",true);
        if(window instanceof WndBag && ((WndBag)window).getSelector()!=null) {
            List<String> candidates=new ArrayList<>();
            for(Item item:Dungeon.hero.belongings) if(((WndBag)window).probeSelectable(item)) candidates.add(itemId(item));
            result.put("kind","item");result.put("prompt",((WndBag)window).getSelector().textPrompt());result.put("item_ids",candidates);
        }
        return result;
    }
    private static Map<String,Object> query(JsonValue input) {
        Map<String,Object> observation=observe();
        if(!Boolean.TRUE.equals(observation.get("input_ready"))) throw new IllegalArgumentException("not_ready");
        Level level=Dungeon.level;int w=level.width();
        Map<Integer,String> memory=explored.get(floorKey());
        String type=input.getString("type","");
        List<Object> cells=new ArrayList<>();
        if(type.equals("region") || type.equals("find")) {
            int x=input.getInt("x",Dungeon.hero.pos%w),y=input.getInt("y",Dungeon.hero.pos/w),radius=input.getInt("radius",4);
            if(radius<0 || radius>20) throw new IllegalArgumentException("radius_must_be_0_to_20");
            String name=input.getString("terrain","");
            if(type.equals("find") && name.isEmpty()) throw new IllegalArgumentException("terrain_required");
            for(Map.Entry<Integer,String> cell:memory.entrySet()) {
                int cx=cell.getKey()%w,cy=cell.getKey()/w;
                if(type.equals("find") ? cell.getValue().equals(name) : Math.abs(cx-x)<=radius && Math.abs(cy-y)<=radius)
                    cells.add(Arrays.asList(cx,cy,cell.getValue(),level.heroFOV[cell.getKey()]));
            }
            return map("floor",Dungeon.branch+":"+Dungeon.depth,"cells",cells,"observation_id",revision);
        }
        boolean frontier=type.equals("frontier");
        if(!type.equals("route") && !frontier) throw new IllegalArgumentException("unknown_query");
        int x=input.getInt("x",-1),y=input.getInt("y",-1);
        if(!frontier && (x<0 || x>=w || y<0 || y>=level.height())) throw new IllegalArgumentException("out_of_bounds");
        int goal=x+y*w,start=Dungeon.hero.pos;
        if(!frontier && !memory.containsKey(goal)) throw new IllegalArgumentException("destination_unexplored");
        int limit=input.getInt("limit",8);
        if(frontier && (limit<1 || limit>32)) throw new IllegalArgumentException("limit_must_be_1_to_32");
        Set<String> walkable=new HashSet<>();
        for(int terrain:new int[]{Terrain.EMPTY,Terrain.EMPTY_DECO,Terrain.EMPTY_SP,Terrain.GRASS,Terrain.HIGH_GRASS,Terrain.FURROWED_GRASS,Terrain.WATER,Terrain.EMBERS,Terrain.OPEN_DOOR,Terrain.DOOR,Terrain.ENTRANCE,Terrain.ENTRANCE_SP,Terrain.EXIT,Terrain.UNLOCKED_EXIT,Terrain.INACTIVE_TRAP}) walkable.add(level.tileName(terrain));
        Set<Integer> occupied=new HashSet<>();
        for(Mob mob:level.mobs) if(level.heroFOV[mob.pos] && mob.sprite!=null && mob.sprite.visible && mob.invisible<=0) occupied.add(mob.pos);
        Map<Integer,Integer> parents=new LinkedHashMap<>();ArrayDeque<Integer> queue=new ArrayDeque<>();
        parents.put(start,start);queue.add(start);
        while(!queue.isEmpty() && (frontier || !parents.containsKey(goal))) {
            int cell=queue.remove();
            for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++) {
                if(dx==0 && dy==0) continue;
                int nx=cell%w+dx,ny=cell/w+dy,next=nx+ny*w;
                if(nx<0 || nx>=w || ny<0 || ny>=level.height() || parents.containsKey(next) || occupied.contains(next) || !walkable.contains(memory.get(next))) continue;
                parents.put(next,cell);queue.add(next);
            }
        }
        if(frontier) {
            List<Object> candidates=new ArrayList<>();
            for(int cell:parents.keySet()) {
                if(!walkable.contains(memory.get(cell))) continue;
                int unknown=0;
                for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++) {
                    if(dx==0 && dy==0) continue;
                    int nx=cell%w+dx,ny=cell/w+dy;
                    if(nx>=0 && nx<w && ny>=0 && ny<level.height() && !memory.containsKey(nx+ny*w)) unknown++;
                }
                if(unknown==0) continue;
                List<Object> path=rememberedPath(parents,start,cell,w);
                candidates.add(map("x",cell%w,"y",cell/w,"terrain",memory.get(cell),"unknown_neighbors",unknown,"steps",path.size(),"path",path));
                if(candidates.size()>=limit) break;
            }
            return map("candidates",candidates,"basis","reachable_known_cells_bordering_unknown","warning","Candidates do not guarantee a passage into unknown terrain. Reobserve each step.","observation_id",revision);
        }
        List<Object> path=new ArrayList<>();
        if(parents.containsKey(goal)) path=rememberedPath(parents,start,goal,w);
        return map("reachable",parents.containsKey(goal),"path",path,"steps",path.size(),"basis","last_observed_terrain","warning","Route is advisory; unseen changes and hidden hazards are unknown. Reobserve before each step.","observation_id",revision);
    }
    private static List<Object> rememberedPath(Map<Integer,Integer> parents,int start,int goal,int width) {
        List<Object> path=new ArrayList<>();
        for(int cell=goal;cell!=start;cell=parents.get(cell)) path.add(Arrays.asList(cell%width,cell/width));
        Collections.reverse(path);return path;
    }
    private static int visibleCell(JsonValue input) {
        Level level=Dungeon.level;
        int x=input.getInt("x",-1),y=input.getInt("y",-1);
        if(x<0 || x>=level.width() || y<0 || y>=level.height() || !level.heroFOV[x+y*level.width()]) throw new IllegalArgumentException("target_not_visible");
        return x+y*level.width();
    }
    private static JsonValue encode(Object value) {
        if(value==null) return new JsonValue(JsonValue.ValueType.nullValue);
        if (value instanceof Map) {
            JsonValue out = new JsonValue(JsonValue.ValueType.object);
            for (Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) out.addChild((String)e.getKey(), encode(e.getValue()));
            return out;
        }
        if (value instanceof Iterable) {
            JsonValue out = new JsonValue(JsonValue.ValueType.array);
            for (Object e : (Iterable<?>)value) out.addChild(encode(e));
            return out;
        }
        if (value instanceof Boolean) return new JsonValue((Boolean)value);
        if (value instanceof Number) return new JsonValue(((Number)value).longValue());
        return new JsonValue((String)value);
    }
    private static Map<String,Object> map(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0; i<values.length; i+=2) result.put((String)values[i], values[i+1]);
        return result;
    }

    private static final String PANEL =
        "<!doctype html><meta charset='utf-8'><title>地牢 AI 验证面板</title>" +
        "<style>body{background:#171b20;color:#e4e9ed;font:16px monospace;margin:24px}" +
        "button{padding:10px;margin:5px}pre{white-space:pre-wrap;max-height:300px;overflow:auto}canvas{width:100%;max-width:800px;height:auto;background:#242c33;image-rendering:pixelated}</style>" +
        "<h1>地牢 AI 验证面板</h1><p>仅显示当前玩家视野。方向：右为 x 增大，下为 y 增大。</p>" +
        "<button onclick='start()'>开始验证对局（战士）</button><button onclick='waitTurn()'>等待一回合</button><button onclick='continueStory()'>继续楼层说明</button>" +
        "<p id='status'>连接中</p><canvas id='map' width='800' height='600'></canvas><pre id='state'></pre>" +
        "<script>async function continueStory(){await request('/action',{type:'continue'});}let obs;async function start(){await request('/start',{});}async function waitTurn(){if(obs&&obs.ready)await request('/action',{observation_id:obs.observation_id,type:'wait',decision:'玩家通过验证面板选择等待一次。'});}" +
        "async function request(p,b){let r=await fetch(p,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});document.getElementById('status').textContent=JSON.stringify(await r.json());}" +
        "let remembered={},mapCursor='';async function poll(){try{obs=await(await fetch('/observe')).json();let m=await(await fetch('/map?after='+mapCursor)).json();if(m.cursor){mapCursor=m.cursor;if(m.reset)remembered=m.changes;else{for(let k in m.changes){if(k==='tiles')Object.assign(remembered.tiles,m.changes.tiles);else remembered[k]=m.changes[k];}}}document.getElementById('state').textContent=JSON.stringify(obs,null,2);" +
        "if(obs.hero){document.getElementById('status').textContent='楼层 '+obs.depth+' 生命 '+obs.hero.hp+'/'+obs.hero.max_hp+' 可操作 '+obs.ready;" +
        "let c=document.getElementById('map'),g=c.getContext('2d');g.clearRect(0,0,c.width,c.height);let s=28,h=obs.hero;" +
        "if(remembered.tiles){let w=remembered.size[0];for(let id in remembered.tiles){let name=remembered.legend[remembered.tiles[id]],x=(id%w-h.x+13)*s,y=(Math.floor(id/w)-h.y+10)*s;g.fillStyle=name.includes('墙')?'#40464e':'#252e37';g.fillRect(x,y,s,s);g.strokeStyle='#444e57';g.strokeRect(x,y,s,s);g.fillStyle='#78848e';g.font='10px monospace';g.fillText(name.slice(0,4),x+1,y+17);}}for(let a of obs.cells){let x=(a[0]-h.x+13)*s,y=(a[1]-h.y+10)*s;g.strokeStyle='#9eafbd';g.strokeRect(x,y,s,s);g.fillStyle='#e3ecf3';g.font='10px monospace';g.fillText(a[2].slice(0,4),x+1,y+17);}" +
        "g.fillStyle='#6fc69a';g.fillRect(13*s+7,10*s+7,14,14);for(let e of obs.entities){g.fillStyle='#e78e79';g.fillRect((e.x-h.x+13)*s+7,(e.y-h.y+10)*s+7,14,14);}}}catch(e){document.getElementById('status').textContent=e.message;}setTimeout(poll,700);}poll();" +
        "document.getElementById('map').onclick=async e=>{if(!obs||!obs.ready)return;let r=e.target.getBoundingClientRect(),dx=Math.floor((e.clientX-r.left)*800/r.width/28)-13,dy=Math.floor((e.clientY-r.top)*600/r.height/28)-10;" +
        "let enemy=obs.entities.some(a=>a.x===obs.hero.x+dx&&a.y===obs.hero.y+dy);await request('/action',{observation_id:obs.observation_id,type:enemy?'attack':'move',dx,dy,decision:'玩家通过验证面板选择相邻格'+(enemy?'攻击。':'移动。')});};</script>";
}
