package scripting;

import client.MapleClient;
import client.inventory.Equip;
import client.inventory.Item;
import client.inventory.MapleInventoryType;
import constants.GameConstants;
import handling.channel.ChannelServer;
import java.awt.Point;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import server.MapleCarnivalFactory;
import server.MapleCarnivalFactory.MCSkill;
import server.MapleItemInformationProvider;
import server.Randomizer;
import server.life.MapleLifeFactory;
import server.life.MapleMonster;
import server.maps.MapleReactor;
import server.maps.ReactorDropEntry;
import tools.FileoutputUtil;
import tools.packet.CField;

public class ReactorActionManager extends AbstractPlayerInteraction {

    private final MapleReactor reactor;

    public ReactorActionManager(MapleClient c, MapleReactor reactor) {
        super(c, reactor.getReactorId(), c.getPlayer().getMapId(), null);
        this.reactor = reactor;
    }

    // only used for meso = false, really. No minItems because meso is used to fill
    // the gap
    public void dropItems() {
        dropItems(false, 0, 0, 0, 0);
    }

    public void dropItems(boolean meso, int mesoChance, int minMeso, int maxMeso) {
        dropItems(meso, mesoChance, minMeso, maxMeso, 0);
    }

    public void dropItems(boolean meso, int mesoChance, int minMeso, int maxMeso, int minItems) {
        final List<ReactorDropEntry> chances = ReactorScriptManager.getInstance().getDrops(reactor.getReactorId());
        final List<ReactorDropEntry> items = new LinkedList<>();

        if (meso) {
            if (Math.random() < (1 / (double) mesoChance)) {
                items.add(new ReactorDropEntry(0, mesoChance, -1, 0));
            }
        }

        int numItems = 0;
        // narrow list down by chances
        final Iterator<ReactorDropEntry> iter = chances.iterator();
        // for (DropEntry d : chances){
        while (iter.hasNext()) {
            ReactorDropEntry d = iter.next();
            if (d.channelType != 0 && reactor.getMap() != null) {
                ChannelServer ch = ChannelServer.getInstance(reactor.getMap().getChannel());
                if (ch != null && !ch.getChannelType().check(d.channelType)) {
                    continue;
                }
            }
            if (Math.random() < (1 / (double) d.chance)
                    && (d.questid <= 0 || getPlayer().getQuestStatus(d.questid) == 1)) {
                numItems++;
                items.add(d);
            }
        }

        // if a minimum number of drops is required, add meso
        while (items.size() < minItems) {
            items.add(new ReactorDropEntry(0, mesoChance, -1, 0));
            numItems++;
        }
        final Point dropPos = reactor.getPosition();

        dropPos.x -= (12 * numItems);

        int range, mesoDrop;
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        for (final ReactorDropEntry d : items) {
            if (d.itemId == 0) {
                range = maxMeso - minMeso;
                mesoDrop = Randomizer.nextInt(range) + minMeso * ChannelServer.getInstance(getClient().getChannel())
                        .getMesoRate(getClient().getPlayer() != null ? getClient().getPlayer().getWorld() : 0);
                reactor.getMap().spawnMesoDrop(mesoDrop, dropPos, reactor, getPlayer(), false, (byte) 0);
            } else {
                Item drop;
                if (GameConstants.getInventoryType(d.itemId) != MapleInventoryType.EQUIP) {
                    drop = new Item(d.itemId, (byte) 0, (short) 1, 0);
                } else {
                    drop = ii.randomizeStats((Equip) ii.getEquipById(d.itemId));
                }
                drop.setGMLog("從反應堆" + reactor.getReactorId() + "中掉寶,地圖" + getPlayer().getMap() + "時間 "
                        + FileoutputUtil.CurrentReadable_Time());
                reactor.getMap().spawnItemDrop(reactor, getPlayer(), drop, dropPos, false, false);
            }
            dropPos.x += 25;
        }
    }

    public void dropSingleItem(int itemId) {
        Item drop;
        if (GameConstants.getInventoryType(itemId) != MapleInventoryType.EQUIP) {
            drop = new Item(itemId, (byte) 0, (short) 1, 0);
        } else {
            drop = MapleItemInformationProvider.getInstance()
                    .randomizeStats((Equip) MapleItemInformationProvider.getInstance().getEquipById(itemId));
        }
        drop.setGMLog("從反應堆" + reactor.getReactorId() + "中掉寶,地圖" + getPlayer().getMap() + "時間 "
                + FileoutputUtil.CurrentReadable_Time());
        reactor.getMap().spawnItemDrop(reactor, getPlayer(), drop, reactor.getPosition(), false, false);
    }

    @Override
    public void spawnNpc(int npcId) {
        spawnNpc(npcId, getPosition());
    }

    // returns slightly above the reactor's position for monster spawns
    public Point getPosition() {
        Point pos = reactor.getPosition();
        pos.y -= 10;
        return pos;
    }

    public MapleReactor getReactor() {
        return reactor;
    }

    public int hitBigby() {
        MapleReactor bigby = null;
        for (MapleReactor r : getMap().getAllReactor()) {
            if (r.getReactorId() == 1301000) {
                bigby = r;
            }
        }
        if (bigby != null) {
            bigby.forceHitReactor(c.getPlayer(), (byte) (bigby.getState() + 1));
            if (bigby.getState() == 10) {
                return 2;
            }
            return 1;
        }
        return 0;
    }

    public void spawnZakum() {
        reactor.getMap().spawnZakum(getPosition().x, getPosition().y);
    }

    public void spawnFakeMonster(int id) {
        spawnFakeMonster(id, 1, getPosition());
    }

    // summon one monster, remote location
    public void spawnFakeMonster(int id, int x, int y) {
        spawnFakeMonster(id, 1, new Point(x, y));
    }

    // multiple monsters, reactor location
    public void spawnFakeMonster(int id, int qty) {
        spawnFakeMonster(id, qty, getPosition());
    }

    // multiple monsters, remote location
    public void spawnFakeMonster(int id, int qty, int x, int y) {
        spawnFakeMonster(id, qty, new Point(x, y));
    }

    // handler for all spawnFakeMonster
    private void spawnFakeMonster(int id, int qty, Point pos) {
        for (int i = 0; i < qty; i++) {
            reactor.getMap().spawnFakeMonsterOnGroundBelow(MapleLifeFactory.getMonster(id), pos);
        }
    }

    public void killAll() {
        reactor.getMap().killAllMonsters(true);
    }

    public void killMonster(int monsId) {
        reactor.getMap().killMonster(monsId);
    }

    public void killReactor(int reactId) {
        reactor.getMap().getReactorById(reactId).delayedDestroyReactor(1);
    }

    // summon one monster on reactor location
    @Override
    public void spawnMonster(int id) {
        spawnMonster(id, 1, getPosition());
    }

    // summon monsters on reactor location
    @Override
    public void spawnMonster(int id, int qty) {
        spawnMonster(id, qty, getPosition());
    }

    public void dispelAllMonsters(final int num) { // dispels all mobs, cpq
        final MCSkill skil = MapleCarnivalFactory.getInstance().getGuardian(num);
        if (skil != null) {
            for (MapleMonster mons : getMap().getAllMonstersThreadsafe()) {
                mons.dispelSkill(skil.getSkill());
            }
        }
    }

    public void cancelHarvest(boolean succ) {
        getPlayer().setFatigue((byte) (getPlayer().getFatigue() + 1));
        getPlayer().getMap().broadcastMessage(getPlayer(), CField.onGatherActionSet(getPlayer().getId(), 0), false);
        getPlayer().getMap().broadcastMessage(CField.harvestResultEffect(getPlayer().getId()));
        getPlayer().getMap().broadcastMessage(CField.harvestResult(getPlayer().getId(), succ));
    }

    public void doHarvest() {
        if (getPlayer().getFatigue() >= 200
                || getReactor().getTruePosition().distanceSq(getPlayer().getTruePosition()) > 10000) {
            return;
        }
        final int pID = getReactor().getReactorId() < 200000 ? 92000000 : 92010000;
        final String pName = (getReactor().getReactorId() < 200000 ? "藥草採集" : "採礦");
        final int he = getPlayer().getProfessionLevel(pID);
        if (he <= 0) {
            return;
        }
        final Item item = getInventory(1).getItem((short) getPlayer().getStat().harvestingTool);
        if (item != null && item.getItemId() / 10000 != (getReactor().getReactorId() < 200000 ? 150 : 151)) {
            return;
        }
        int hm = getReactor().getReactorId() % 100;
        int successChance = 90 + ((he - hm) * 10);
        if (getReactor().getReactorId() % 100 == 10) {
            hm = 1;
            successChance = 100;
        } else if (getReactor().getReactorId() % 100 == 11) {
            hm = 10;
            successChance -= 40;
        }
        getPlayer().getStat().checkEquipDurabilitys(getPlayer(), -1, true);
        int masteryIncrease = (hm - he) * 2 + 20;
        final boolean succ = randInt(100) < successChance;
        if (!succ) {
            masteryIncrease /= 10;
            dropSingleItem(getReactor().getReactorId() < 200000 ? 4022023 : 4010010);
        } else {
            dropItems();
            if (getReactor().getReactorId() < 200000) {
                addTrait("sense", 5);
                if (Randomizer.nextInt(10) == 0) {
                    dropSingleItem(2440000);
                }
                if (Randomizer.nextInt(100) == 0) {
                    dropSingleItem(4032933);
                }
            } else {
                addTrait("insight", 5);
                if (Randomizer.nextInt(10) == 0) {
                    dropSingleItem(2440001); // IMP
                }
            }
            int[] 怪物公園入場券碎片 = {4001513, 4001515, 4001521};
            if (Randomizer.nextInt(10) <= 1) {
                dropSingleItem(怪物公園入場券碎片[Randomizer.nextInt(怪物公園入場券碎片.length)]);
            }
        }
        cancelHarvest(succ);
        playerMessage(-5, pName + "的熟練度增加。 (+" + masteryIncrease + ")");
        if (getPlayer().addProfessionExp(pID, masteryIncrease)) {
            playerMessage(-5, pName + "已升級。");
        }
    }
}