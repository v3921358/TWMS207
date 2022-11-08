/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package handling.world;

import client.MapleCharacter;
import client.MapleClient;
import constants.GameConstants;
import java.io.File;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import server.Randomizer;
import server.Timer.MapTimer;
import tools.CheckCodeImageCreator;
import tools.Pair;
import tools.StringTool;
import tools.packet.CWvsContext;

/**
 *
 * @author pungin
 */
public class MapleAntiMacro {

    public static class MapleAntiMacroInfo {

        private final MapleCharacter source;
        private final int mode;
        private String code;
        private final long startTime;
        private ScheduledFuture<?> schedule;
        private int timesLeft = 2;

        MapleAntiMacroInfo(MapleCharacter from, int mode, String code, long time, ScheduledFuture<?> schedule) {
            source = from;
            this.mode = mode;
            this.code = code;
            startTime = time;
            this.schedule = schedule;
        }

        public MapleCharacter getSourcePlayer() {
            return source;
        }

        public int antiMode() {
            return mode;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }

        public long getStartTime() {
            return startTime;
        }

        public void setSchedule(ScheduledFuture<?> schedule) {
            cancelSchedule();
            this.schedule = schedule;
        }

        public void cancelSchedule() {
            if (schedule != null) {
                schedule.cancel(false);
            }
        }

        public int antiFailure() {
            return --timesLeft;
        }

        public int getTimesLeft() {
            return timesLeft - 1;
        }
    }

    private static final Map<String, MapleAntiMacroInfo> antiPlayers = new HashMap<>();
    private static final Map<String, Long> lastAntiTime = new HashMap<>();

    // 測謊機類型
    public final static byte SYSTEM_ANTI = 0; // 系統自動偵測
    public final static byte ITEM_ANTI = 1; // 道具測謊
    public final static byte GM_SKILL_ANTI = 2; // 管理員技能測謊

    // 角色測謊狀態常量
    public final static int CAN_ANTI = 0; // 可測謊
    public final static int NON_ATTACK = 1; // 非攻擊狀態
    public final static int ANTI_COOLING = 2; // 已通過測謊
    public final static int ANTI_NOW = 3; // 正在測謊
    public final static int BOSS_MAP = 4; // BOSS地圖

    public static int getCharacterState(MapleCharacter chr) {
        // 判斷是否正在被測謊
        if (isAntiNow(chr.getName())) {
            return ANTI_NOW;
        }

        // 判斷冷卻狀態
        if (isCooling(chr.getName())) {
            return ANTI_COOLING;
        }

        // 判斷非攻擊狀態
        if (!chr.isAttacking()) {
            return NON_ATTACK;
        }

        // BOSS地圖
        if (GameConstants.isBossMap(chr.getMapId())) {
            return BOSS_MAP;
        }

        // 可測謊
        return CAN_ANTI;
    }

    public static void updateCooling(String name) {
        lastAntiTime.put(name, System.currentTimeMillis());
    }

    public static boolean isCooling(String name) {
        if (lastAntiTime.containsKey(name)) {
            if (System.currentTimeMillis() - lastAntiTime.get(name) < (20 + Randomizer.nextInt(10)) * 60 * 1000) { // 20~30分鐘冷卻
                return true;
            } else {
                lastAntiTime.remove(name);
            }
        }
        return false;
    }

    public static boolean isAntiNow(String name) {
        if (antiPlayers.containsKey(name)
                && System.currentTimeMillis() - antiPlayers.get(name).getStartTime() < 5 * 60 * 1000) { // 5分鐘
            return true;
        }
        return false;
    }

    public static boolean startAnti(MapleCharacter chr, MapleCharacter victim, byte mode) {
        int antiState = MapleAntiMacro.getCharacterState(victim);
        switch (antiState) {
            case MapleAntiMacro.CAN_ANTI: {
                break;
            }
            case MapleAntiMacro.NON_ATTACK: {
                if (chr != null) {
                    chr.getClient().getSession().writeAndFlush(CWvsContext.AntiMacro.nonAttack());
                }
                return false;
            }
            case MapleAntiMacro.ANTI_COOLING:
            case MapleAntiMacro.BOSS_MAP: {
                if (chr != null) {
                    chr.getClient().getSession().writeAndFlush(CWvsContext.AntiMacro.alreadyPass());
                }
                return false;
            }
            case MapleAntiMacro.ANTI_NOW: {
                if (chr != null) {
                    chr.getClient().getSession().writeAndFlush(CWvsContext.AntiMacro.antiMacroNow());
                }
                return false;
            }
            default: {
                System.out.println("測謊機狀態出現未知類型：" + antiState);
                return false;
            }
        }

        Pair<String, File> checkCode = CheckCodeImageCreator.createCheckCode(Randomizer.nextInt(100) > 80);
        MapleAntiMacroInfo ami = new MapleAntiMacroInfo(chr, mode, checkCode.getLeft(), System.currentTimeMillis(),
                MapTimer.getInstance().schedule(() -> {
                    if (antiPlayers.containsKey(victim.getName())) {
                        antiFailure(victim);
                    }
                }, 5 * 60 * 1000));
        antiPlayers.put(victim.getName(), ami);
        victim.getClient().getSession()
                .writeAndFlush(CWvsContext.AntiMacro.getImage(mode, checkCode.getRight(), ami.getTimesLeft()));
        checkCode.getRight().delete();
        if (chr != null) {
            chr.getClient().getSession().writeAndFlush(CWvsContext.AntiMacro.antiMsg(mode, victim.getName()));
        }
        return true;
    }

    public static void antiSuccess(MapleCharacter victim) {
        MapleAntiMacroInfo ami = null;
        if (antiPlayers.containsKey(victim.getName())) {
            ami = antiPlayers.get(victim.getName());
            if (ami.antiMode() == ITEM_ANTI) {
                victim.gainMeso(5000, true);
            }
            MapleCharacter chr = ami.getSourcePlayer();
            if (chr != null) {
                chr.getClient().getSession().writeAndFlush(CWvsContext.AntiMacro.successMsg(2, victim.getName()));
            }
        }
        victim.setAntiMacroFailureTimes(0);
        victim.getClient().getSession()
                .writeAndFlush(CWvsContext.AntiMacro.success(ami == null ? SYSTEM_ANTI : ami.antiMode()));
        stopAnti(victim.getName());
        updateCooling(victim.getName());
    }

    public static void antiFailure(MapleCharacter victim) {
        MapleAntiMacroInfo ami = null;
        if (antiPlayers.containsKey(victim.getName())) {
            ami = antiPlayers.get(victim.getName());
            MapleCharacter chr = ami.getSourcePlayer();
            if (chr != null && ami.antiMode() == GM_SKILL_ANTI) {
                chr.getClient().getSession().writeAndFlush(CWvsContext.AntiMacro.failureScreenshot(victim.getName()));
            }
        }
        if (victim.addAntiMacroFailureTimes() < 5) {
            victim.changeMap(victim.getMap().getReturnMap());
            victim.getClient().getSession()
                    .writeAndFlush(CWvsContext.AntiMacro.failure(ami == null ? SYSTEM_ANTI : ami.antiMode()));
            victim.dropMessage(5, "你本次測謊未通過，連續5次沒通過將會被封鎖帳號。");
        } else {
            victim.setAntiMacroFailureTimes(0);
            int warning = StringTool.parseInt(victim.getClient().getValue("warning")) + 1;
            victim.getClient().setValue("warning", String.valueOf(warning));
            final Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DATE, warning < 2 ? 7 : warning < 3 ? 30 : 365);
            victim.ban("測謊機連續失敗5次。", cal, MapleClient.BanReason.系統鎖定, true, false, false, false);
            victim.getClient().getSession().close();
            victim.getClient().disconnect(true, false);
        }
        stopAnti(victim.getName());
    }

    public static void stopAnti(String name) {
        if (antiPlayers.containsKey(name)) {
            antiPlayers.get(name).cancelSchedule();
        }
        antiPlayers.remove(name);
    }

    public static void antiReduce(MapleCharacter victim) {
        if (antiPlayers.containsKey(victim.getName())) {
            MapleAntiMacroInfo ami = antiPlayers.get(victim.getName());
            if (ami.antiFailure() > 0) {
                refreshCode(victim);
            } else {
                antiFailure(victim);
            }
        }
    }

    public static boolean verifyCode(String name, String code) {
        if (!antiPlayers.containsKey(name)) {
            return false;
        }
        return antiPlayers.get(name).getCode().equalsIgnoreCase(code);
    }

    public static void refreshCode(MapleCharacter victim) {
        if (antiPlayers.containsKey(victim.getName())) {
            MapleAntiMacroInfo ami = antiPlayers.get(victim.getName());
            Pair<String, File> checkCode = CheckCodeImageCreator.createCheckCode(Randomizer.nextInt(100) > 80);
            ami.setCode(checkCode.getLeft());
            ami.setSchedule(MapTimer.getInstance().schedule(() -> {
                if (antiPlayers.containsKey(victim.getName())) {
                    antiFailure(victim);
                }
            }, 5 * 60 * 1000));
            victim.getClient().getSession().writeAndFlush(
                    CWvsContext.AntiMacro.getImage((byte) ami.antiMode(), checkCode.getRight(), ami.getTimesLeft()));
            checkCode.getRight().delete();
        }
    }
}
