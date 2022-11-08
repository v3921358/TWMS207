/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc> 
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package server;

import handling.channel.ChannelServer;

import java.awt.Point;

import scripting.PortalScriptManager;
import server.maps.MapleMap;
import tools.packet.CWvsContext;
import client.MapleClient;
import client.anticheat.CheatingOffense;

public class MaplePortal {

    public static final int MAP_PORTAL = 2;
    public static final int DOOR_PORTAL = 6;
    private String name, target, scriptName;
    private Point position;
    private int targetmap, type, id;
    private boolean portalState = true;

    public MaplePortal(final int type) {
        this.type = type;
    }

    public final int getId() {
        return id;
    }

    public final void setId(int id) {
        this.id = id;
    }

    public final String getName() {
        return name;
    }

    public final Point getPosition() {
        return position;
    }

    public final String getTarget() {
        return target;
    }

    public final int getTargetMapId() {
        return targetmap;
    }

    public final int getType() {
        return type;
    }

    public final String getScriptName() {
        return scriptName;
    }

    public final void setName(final String name) {
        this.name = name;
    }

    public final void setPosition(final Point position) {
        this.position = position;
    }

    public final void setTarget(final String target) {
        this.target = target;
    }

    public final void setTargetMapId(final int targetmapid) {
        this.targetmap = targetmapid;
    }

    public final void setScriptName(final String scriptName) {
        this.scriptName = scriptName;
    }

    public final void enterPortal(final MapleClient c) {
        if (getPosition().distanceSq(c.getPlayer().getPosition()) > 70000) {
            if (c.getPlayer().isShowErr()) {
                c.getPlayer().showInfo("無效傳送", true,
                        "到傳送點的距離:" + getPosition().distanceSq(c.getPlayer().getPosition()) + "限制最大距離:" + 40000
                        + "傳送點坐標(" + getPosition().x + "," + getPosition().y + ")角色坐標("
                        + c.getPlayer().getPosition().x + "," + c.getPlayer().getPosition().y + ")");
            }
            switch (c.getPlayer().getMapId()) {
                case 4000010:
                case 4000014:
                case 900020100:
                case 927020071:
                    break;
                default:
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    c.getPlayer().getCheatTracker().registerOffense(CheatingOffense.USING_FARAWAY_PORTAL);
                    return;
            }
            if (c.getPlayer().isShowErr()) {
                c.getPlayer().dropMessage(5, "此BUG未修復");
            }
        }
        final MapleMap currentmap = c.getPlayer().getMap();
        if (!c.getPlayer().hasBlockedInventory() && portalState) {
            if (getScriptName() != null) {
                c.getPlayer().checkFollow();
                try {
                    PortalScriptManager.getInstance().executePortalScript(this, c);
                } catch (Exception Ex) {
                    Ex.printStackTrace();
                }
            } else if (getTargetMapId() != 999999999) {
                MapleMap to = ChannelServer.getInstance(c.getChannel()).getMapFactory().getMap(getTargetMapId());

                if (to == null) {
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
                }
                if (!c.getPlayer().isIntern()) {
                    if (to.getLevelLimit() > 0 && to.getLevelLimit() > c.getPlayer().getLevel()) {
                        c.getPlayer().dropMessage(-1, "You are too low of a level to enter this place.");
                        c.getSession().writeAndFlush(CWvsContext.enableActions());
                        return;
                    }
                }
                if (c.getPlayer().getMapId() == 109010100 || c.getPlayer().getMapId() == 109010104
                        || c.getPlayer().getMapId() == 109020001) {
                    c.getPlayer().dropMessage(5, "You may not exit the event map.");
                    c.getSession().writeAndFlush(CWvsContext.enableActions());
                    return;
                }
                c.getPlayer().changeMapPortal(to,
                        to.getPortal(getTarget()) == null ? to.getPortal(0) : to.getPortal(getTarget()));
            }
        }
        if (c != null && c.getPlayer() != null && c.getPlayer().getMap() == currentmap) { // Character is still on the
            // same map.
            c.getSession().writeAndFlush(CWvsContext.enableActions());
        }
    }

    public boolean getPortalState() {
        return portalState;
    }

    public void setPortalState(boolean ps) {
        this.portalState = ps;
    }
}
