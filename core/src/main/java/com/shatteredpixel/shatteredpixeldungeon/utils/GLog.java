/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.utils;

import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.utils.DeviceCompat;
import com.watabou.utils.Signal;

public class GLog {
    private static final java.util.ArrayList<String> probeMessages = new java.util.ArrayList<>();
    private static final java.util.ArrayList<java.util.Map<String,Object>> probeEvents=new java.util.ArrayList<>();
    private static long probeSequence;
    private static long probeEpoch;
    private static long probeEpochStart;
    private static void retainEvent(java.util.Map<String,Object> event) {
        event.put("id",++probeSequence);probeEvents.add(event);
        while(probeEvents.size()>128)probeEvents.remove(0);
    }
    /** Records only a status text/icon which was actually created for the player's screen. */
    public static void probeVisibleStatus(java.util.Map<String,Object> target,String text,int color,int icon) {
        if(!Boolean.getBoolean("spd.ai.probe") || target==null)return;
        java.util.Map<String,Object> event=new java.util.LinkedHashMap<>();
        java.util.Map<String,Object> publicTarget=new java.util.LinkedHashMap<>(target);
        boolean defenseMessage=Boolean.TRUE.equals(publicTarget.remove("defense_message"));
        event.put("kind","visual");event.put("source","floating_text");event.put("target",publicTarget);
        event.put("text",text);event.put("color",color);event.put("icon_id",icon);
        int hitIcon=icon>=54 && icon<=65?icon-18:icon;
        boolean hitReason=hitIcon>=36 && hitIcon<=47;
        boolean damage=icon>=0 && icon<=3 || hitReason;
        boolean dot=icon>=5 && icon<=17;
        String category=defenseMessage || icon>=72 && icon<=82?"miss":damage?"damage":dot?"damage_over_time":icon==18?"healing":icon==19?"shield_gain":"status";
        event.put("category",category);
        if(category.equals("miss"))event.put("outcome","miss");
        if(damage)event.put("outcome","hit");
        if(hitIcon==45)event.put("surprise",true);
        if((damage || dot) && text.matches("[0-9]+"))event.put("displayed_damage",Integer.parseInt(text));
        if((icon==18 || icon==19) && text.matches("[0-9]+"))event.put("displayed_amount",Integer.parseInt(text));
        if(dot)event.put("damage_type",new String[]{"hunger","burning","shock","frost","water","bleeding","toxic","corrosion","poison","ooze","deferred","corruption","amulet"}[icon-5]);
        synchronized(probeMessages){retainEvent(event);}
    }
    public static void clearProbeMessages() {
        synchronized(probeMessages){probeMessages.clear();probeEvents.clear();probeEpoch++;probeEpochStart=probeSequence;}
    }
    public static java.util.Map<String,Object> probeEvents(long after) {
        synchronized(probeMessages) {
            java.util.List<Object> events=new java.util.ArrayList<>();
            for(java.util.Map<String,Object> event:probeEvents) if((Long)event.get("id")>after) events.add(new java.util.LinkedHashMap<>(event));
            java.util.Map<String,Object> result=new java.util.LinkedHashMap<>();
            result.put("events",events);result.put("last_id",probeSequence);result.put("epoch",probeEpoch);
            result.put("gap",!probeEvents.isEmpty() && Math.max(after,probeEpochStart)<(Long)probeEvents.get(0).get("id")-1);
            return result;
        }
    }
    public static java.util.List<String> probeMessages() {
        synchronized (probeMessages) { return new java.util.ArrayList<>(probeMessages); }
    }


	public static final String TAG = "GAME";
	
	public static final String POSITIVE		= "++ ";
	public static final String NEGATIVE		= "-- ";
	public static final String WARNING		= "** ";
	public static final String HIGHLIGHT	= "@@ ";

	public static final String NEW_LINE	    = "\n";
	
	public static Signal<String> update = new Signal<>();

	public static void newLine(){
		update.dispatch( NEW_LINE );
	}
	
	public static void i( String text, Object... args ) {
		
		if (args.length > 0) {
			text = Messages.format( text, args );
		}
		
		if (Boolean.getBoolean("spd.ai.probe")) {
            synchronized (probeMessages) {
                probeMessages.add(text);
                java.util.Map<String,Object> event=new java.util.LinkedHashMap<>();event.put("kind","log");event.put("text",text);retainEvent(event);
                while (probeMessages.size() > 12) probeMessages.remove(0);
            }
        }
        DeviceCompat.log( TAG, text );
		update.dispatch( text );
	}
	
	public static void p( String text, Object... args ) {
		i( POSITIVE + text, args );
	}
	
	public static void n( String text, Object... args ) {
		i( NEGATIVE + text, args );
	}
	
	public static void w( String text, Object... args ) {
		i( WARNING + text, args );
	}
	
	public static void h( String text, Object... args ) {
		i( HIGHLIGHT + text, args );
	}
}
