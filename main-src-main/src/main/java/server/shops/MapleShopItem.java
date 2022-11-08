package server.shops;

import client.inventory.Item;
import constants.ItemConstants;
import server.MapleItemInformationProvider;

public class MapleShopItem {

    private final short buyable;
    private final short quantity;
    private final int itemId;
    private final int price;
    private final short slot;
    private final int reqItem;
    private final int reqItemQ;
    private final int category;
    private final int minLevel;
    private final int expiration;
    private final byte rank;
    private final boolean potential;

    public MapleShopItem(Item item, byte rank) {
        this.buyable = item.getQuantity();
        this.quantity = item.getQuantity();
        this.itemId = item.getItemId();
        this.price = ItemConstants.getItemPrice(item.getItemId(), item.getQuantity());
        this.slot = 0;
        this.reqItem = 0;
        this.reqItemQ = 0;
        this.rank = rank;
        this.category = 0;
        this.minLevel = 0;
        this.expiration = 0;
        this.potential = false;
    }

    public MapleShopItem(int itemId, int price, short slot, short buyable) {
        this.buyable = buyable;
        this.quantity = 1;
        this.itemId = itemId;
        this.price = Math.max(price, ItemConstants.getItemPrice(itemId, quantity));
        this.slot = slot;
        this.reqItem = 0;
        this.reqItemQ = 0;
        this.rank = (byte) 0;
        this.category = 0;
        this.minLevel = 0;
        this.expiration = 0;
        this.potential = false;
    }

    public MapleShopItem(short buyable, short quantity, int itemId, int price, short slot, int reqItem, int reqItemQ,
            byte rank, int category, int minLevel, int expiration, boolean potential) {
        this.buyable = buyable;
        this.quantity = quantity;
        this.itemId = itemId;
        this.price = ItemConstants.類型.可充值道具(itemId) && price == 0 ? price
                : Math.max(price, ItemConstants.getItemPrice(itemId,
                        quantity == 0 ? MapleItemInformationProvider.getInstance().getSlotMax(itemId) : quantity));
        this.slot = slot;
        this.reqItem = reqItem;
        this.reqItemQ = reqItemQ;
        this.rank = rank;
        this.category = category;
        this.minLevel = minLevel;
        this.expiration = expiration;
        this.potential = potential;
    }

    public short getBuyable() {
        return buyable;
    }

    public short getQuantity() {
        return quantity;
    }

    public int getItemId() {
        return itemId;
    }

    public int getPrice() {
        return price;
    }

    public short getSlot() {
        return slot;
    }

    public int getReqItem() {
        return reqItem;
    }

    public int getReqItemQ() {
        return reqItemQ;
    }

    public byte getRank() {
        return rank;
    }

    public int getCategory() {
        return category;
    }

    public int getMinLevel() {
        return minLevel;
    }

    public int getExpiration() {
        return expiration;
    }

    public boolean hasPotential() {
        return potential;
    }
}
