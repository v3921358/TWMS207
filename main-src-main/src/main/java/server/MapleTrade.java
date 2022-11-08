package server;

import client.MapleCharacter;
import client.MapleClient;
import client.inventory.Item;
import client.inventory.ItemFlag;
import client.inventory.MapleInventoryType;
import constants.GameConstants;
import constants.ItemConstants;
import handling.world.World;
import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.List;
import server.commands.CommandProcessor;
import server.commands.CommandType;
import tools.packet.CField;
import tools.packet.CWvsContext;
import tools.packet.PlayerShopPacket;

public class MapleTrade {

    private MapleTrade partner = null;
    private final List<Item> items = new LinkedList<>();
    private List<Item> exchangeItems;
    private long meso = 0;
    private long exchangeMeso = 0;
    private boolean locked = false;
    private boolean inTrade = false;
    private final WeakReference<MapleCharacter> chr;
    private final byte tradingslot;

    public MapleTrade(byte tradingslot, MapleCharacter chr) {
        this.tradingslot = tradingslot;
        this.chr = new WeakReference<>(chr);
    }

    public final void CompleteTrade() {
        if (this.exchangeItems != null) {
            List<Item> itemz = new LinkedList<>(this.exchangeItems);
            for (Item item : itemz) {
                int flag = item.getFlag();

                if (ItemFlag.KARMA.check(flag)) {
                    item.setFlag(flag - ItemFlag.KARMA.getValue());
                }
                MapleInventoryManipulator.addFromDrop(this.chr.get().getClient(), item);
            }
            this.exchangeItems.clear();
        }
        if (this.exchangeMeso > 0) {
            this.chr.get().gainMeso(this.exchangeMeso - GameConstants.getTaxAmount(this.exchangeMeso), false, false);
        }
        this.exchangeMeso = 0;

        this.chr.get().getClient().getSession()
                .writeAndFlush(CField.InteractionPacket.TradeMessage(this.tradingslot, (byte) 7));
    }

    public final void cancel(MapleClient c, MapleCharacter chr) {
        cancel(c, chr, 0);
    }

    public final void cancel(MapleClient c, MapleCharacter chr, int unsuccessful) {
        if (this.items != null) {
            List<Item> itemz = new LinkedList<>(this.items);
            for (Item item : itemz) {
                MapleInventoryManipulator.addFromDrop(c, item);
            }
            this.items.clear();
        }
        if (this.meso > 0) {
            chr.gainMeso(this.meso, false, false);
        }
        this.meso = 0;

        c.getSession().writeAndFlush(CField.InteractionPacket.getTradeCancel(this.tradingslot, unsuccessful));
    }

    public final boolean isLocked() {
        return this.locked;
    }

    public final void setMeso(long meso) {
        if ((this.locked) || (this.partner == null) || (meso <= 0) || (this.meso + meso <= 0)) {
            return;
        }
        if (this.chr.get().getMeso() >= meso) {
            this.chr.get().gainMeso(-meso, false, false);
            this.meso += meso;
            this.chr.get().getClient().getSession()
                    .writeAndFlush(CField.InteractionPacket.getTradeMesoSet((byte) 0, this.meso));
            if (this.partner != null) {
                this.partner.getChr().getClient().getSession()
                        .writeAndFlush(CField.InteractionPacket.getTradeMesoSet((byte) 1, this.meso));
            }
        }
    }

    public final void addItem(Item item) {
        if ((this.locked) || (this.partner == null)) {
            return;
        }
        this.items.add(item);
        this.chr.get().getClient().getSession().writeAndFlush(CField.InteractionPacket.getTradeItemAdd((byte) 0, item));
        if (this.partner != null) {
            this.partner.getChr().getClient().getSession()
                    .writeAndFlush(CField.InteractionPacket.getTradeItemAdd((byte) 1, item));
        }
    }

    public final void chat(String message) throws Exception {
        if (!CommandProcessor.processCommand(chr.get().getClient(), message, CommandType.TRADE)) {
            this.chr.get().dropMessage(-2, this.chr.get().getName() + " : " + message);
            if (this.partner != null) {
                this.partner.getChr().getClient().getSession()
                        .writeAndFlush(PlayerShopPacket.shopChat(this.chr.get().getName() + " : " + message, 1));
            }
        }
        if (this.chr.get().getClient().isMonitored()) {
            World.Broadcast.broadcastGMMessage(CWvsContext.broadcastMsg(6, this.chr.get().getName()
                    + " said in trade with " + this.partner.getChr().getName() + ": " + message));
        } else if ((this.partner != null) && (this.partner.getChr() != null)
                && (this.partner.getChr().getClient().isMonitored())) {
            World.Broadcast.broadcastGMMessage(CWvsContext.broadcastMsg(6, this.chr.get().getName()
                    + " said in trade with " + this.partner.getChr().getName() + ": " + message));
        }
    }

    public final void chatAuto(String message) {
        this.chr.get().dropMessage(-2, message);
        if (this.partner != null) {
            this.partner.getChr().getClient().getSession().writeAndFlush(PlayerShopPacket.shopChat(message, 1));
        }
        if (this.chr.get().getClient().isMonitored()) {
            World.Broadcast.broadcastGMMessage(CWvsContext.broadcastMsg(6, this.chr.get().getName()
                    + " said in trade [Automated] with " + this.partner.getChr().getName() + ": " + message));
        } else if ((this.partner != null) && (this.partner.getChr() != null)
                && (this.partner.getChr().getClient().isMonitored())) {
            World.Broadcast.broadcastGMMessage(CWvsContext.broadcastMsg(6, this.chr.get().getName()
                    + " said in trade [Automated] with " + this.partner.getChr().getName() + ": " + message));
        }
    }

    public final MapleTrade getPartner() {
        return this.partner;
    }

    public final void setPartner(MapleTrade partner) {
        if (this.locked) {
            return;
        }
        this.partner = partner;
    }

    public final MapleCharacter getChr() {
        return this.chr.get();
    }

    public final int getNextTargetSlot() {
        if (this.items.size() >= 9) {
            return -1;
        }
        int ret = 1;
        for (Item item : this.items) {
            if (item.getPosition() == ret) {
                ret++;
            }
        }
        return ret;
    }

    public boolean inTrade() {
        return this.inTrade;
    }

    public final boolean setItems(MapleClient c, Item item, byte targetSlot, int quantity) {
        int target = getNextTargetSlot();
        MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        if ((this.partner == null) || (target == -1) || (ItemConstants.類型.寵物(item.getItemId())) || (isLocked())
                || ((GameConstants.getInventoryType(item.getItemId()) == MapleInventoryType.EQUIP)
                && (quantity != 1))) {
            return false;
        }
        int flag = item.getFlag();
        if (ItemFlag.LOCK.check(flag) || ii.isAccountShared(item.getItemId()) || ii.isAccountShared(item.getItemId())
                || ii.isShareTagEnabled(item.getItemId()) || ii.isSharableOnce(item.getItemId())) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return false;
        }
        if ((ii.isCash(item.getItemId()) || ii.isDropRestricted(item.getItemId()) || ItemFlag.UNTRADABLE.check(flag))
                && !ItemFlag.KARMA.check(flag)) {
            c.getSession().writeAndFlush(CWvsContext.enableActions());
            return false;
        }

        Item tradeItem = item.copy();
        if (ItemConstants.類型.可充值道具(item.getItemId())) {
            tradeItem.setQuantity(item.getQuantity());
            MapleInventoryManipulator.removeFromSlot(c, GameConstants.getInventoryType(item.getItemId()),
                    item.getPosition(), item.getQuantity(), true);
        } else {
            tradeItem.setQuantity((short) quantity);
            MapleInventoryManipulator.removeFromSlot(c, GameConstants.getInventoryType(item.getItemId()),
                    item.getPosition(), (short) quantity, true);
        }
        if (targetSlot < 0) {
            targetSlot = (byte) target;
        } else {
            for (Item itemz : this.items) {
                if (itemz.getPosition() == targetSlot) {
                    targetSlot = (byte) target;
                    break;
                }
            }
        }
        tradeItem.setPosition(targetSlot);
        addItem(tradeItem);
        return true;
    }

    private int check() {
        if (this.chr.get().getMeso() + this.exchangeMeso < 0L) {
            return 1;
        }

        if (this.exchangeItems != null) {
            MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
            byte eq = 0;
            byte use = 0;
            byte setup = 0;
            byte etc = 0;
            byte cash = 0;
            for (Item item : this.exchangeItems) {
                switch (GameConstants.getInventoryType(item.getItemId())) {
                    case EQUIP:
                        eq = (byte) (eq + 1);
                        break;
                    case USE:
                        use = (byte) (use + 1);
                        break;
                    case SETUP:
                        setup = (byte) (setup + 1);
                        break;
                    case ETC:
                        etc = (byte) (etc + 1);
                        break;
                    case CASH:
                        cash = (byte) (cash + 1);
                }

                if ((ii.isPickupRestricted(item.getItemId()))
                        && (this.chr.get().haveItem(item.getItemId(), 1, true, true))) {
                    return 2;
                }
            }
            if ((this.chr.get().getInventory(MapleInventoryType.EQUIP).getNumFreeSlot() < eq)
                    || (this.chr.get().getInventory(MapleInventoryType.USE).getNumFreeSlot() < use)
                    || (this.chr.get().getInventory(MapleInventoryType.SETUP).getNumFreeSlot() < setup)
                    || (this.chr.get().getInventory(MapleInventoryType.ETC).getNumFreeSlot() < etc)
                    || (this.chr.get().getInventory(MapleInventoryType.CASH).getNumFreeSlot() < cash)) {
                return 1;
            }
        }

        return 0;
    }

    public static final void completeTrade(MapleCharacter c) {
        MapleTrade local = c.getTrade();
        MapleTrade partner = local.getPartner();

        if ((partner == null) || (local.locked)) {
            return;
        }
        local.locked = true;
        partner.getChr().getClient().getSession().writeAndFlush(CField.InteractionPacket.getTradeConfirmation());

        partner.exchangeItems = new LinkedList<>(local.items);
        partner.exchangeMeso = local.meso;

        if (partner.isLocked()) {
            int lz = local.check();
            int lz2 = partner.check();
            if ((lz == 0) && (lz2 == 0)) {
                local.CompleteTrade();
                partner.CompleteTrade();
            } else {
                partner.cancel(partner.getChr().getClient(), partner.getChr(), lz == 0 ? lz2 : lz);
                local.cancel(c.getClient(), c, lz == 0 ? lz2 : lz);
            }
            partner.getChr().setTrade(null);
            c.setTrade(null);
        }
    }

    public static final void cancelTrade(MapleTrade Localtrade, MapleClient c, MapleCharacter chr) {
        Localtrade.cancel(c, chr);

        MapleTrade partner = Localtrade.getPartner();
        if ((partner != null) && (partner.getChr() != null)) {
            partner.cancel(partner.getChr().getClient(), partner.getChr());
            partner.getChr().setTrade(null);
        }
        chr.setTrade(null);
    }

    public static final void startTrade(MapleCharacter c) {
        if (c.getTrade() == null) {
            c.setTrade(new MapleTrade((byte) 0, c));
            c.getClient().getSession()
                    .writeAndFlush(CField.InteractionPacket.getTradeStart(c.getClient(), c.getTrade(), (byte) 0));
        } else {
            c.getClient().getSession().writeAndFlush(CWvsContext.broadcastMsg(5, "現在還不能進行."));
        }
    }

    public static final void inviteTrade(MapleCharacter c1, MapleCharacter c2) {
        if ((c1 == null) || (c1.getTrade() == null)) {
            return;
        }
        if ((c2 != null) && (c2.getTrade() == null)) {
            c2.setTrade(new MapleTrade((byte) 1, c2));
            c2.getTrade().setPartner(c1.getTrade());
            c1.getTrade().setPartner(c2.getTrade());
            c2.getClient().getSession().writeAndFlush(CField.InteractionPacket.getTradeInvite(c1));
        } else {
            c1.getClient().getSession().writeAndFlush(CWvsContext.broadcastMsg(5, "對方正在和其他玩家進行交易中。"));
            cancelTrade(c1.getTrade(), c1.getClient(), c1);
        }
    }

    public static final void visitTrade(MapleCharacter c1, MapleCharacter c2) {
        if ((c2 != null) && (c1.getTrade() != null) && (c1.getTrade().getPartner() == c2.getTrade())
                && (c2.getTrade() != null) && (c2.getTrade().getPartner() == c1.getTrade())) {
            c1.getTrade().inTrade = true;
            c2.getClient().getSession().writeAndFlush(PlayerShopPacket.shopVisitorAdd(c1, 1));
            c1.getClient().getSession()
                    .writeAndFlush(CField.InteractionPacket.getTradeStart(c1.getClient(), c1.getTrade(), (byte) 1));
            c1.dropMessage(-2, "系統提示 : 進行楓幣交換請注意手續費");
            c2.dropMessage(-2, "系統提示 : 進行楓幣交換請注意手續費");
            c1.dropMessage(-2, "系統提示 : 使用指令 '/幫助' 可以看到交易可用的指令說明.");
            c2.dropMessage(-2, "系統提示 : 使用指令 '/幫助' 可以看到交易可用的指令說明.");
        } else {
            c1.getClient().getSession().writeAndFlush(CWvsContext.broadcastMsg(5, "對方已經取消了交易。"));
        }
    }

    public static final void declineTrade(MapleCharacter c) {
        MapleTrade trade = c.getTrade();
        if (trade != null) {
            if (trade.getPartner() != null) {
                MapleCharacter other = trade.getPartner().getChr();
                if ((other != null) && (other.getTrade() != null)) {
                    other.getTrade().cancel(other.getClient(), other);
                    other.setTrade(null);
                    other.dropMessage(5, c.getName() + " 拒絕了你的交易邀請。");
                }
            }
            trade.cancel(c.getClient(), c);
            c.setTrade(null);
        }
    }
}
