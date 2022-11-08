package handling.world.guild;

import client.MapleCharacter;
import client.MapleCharacterUtil;
import client.MapleClient;
import client.skill.SkillFactory;
import constants.GameConstants;
import database.ManagerDatabasePool;
import extensions.temporary.GuildOpcode;
import handling.world.World;
import handling.world.guild.MapleBBSThread.MapleBBSReply;
import server.MapleStatEffect;
import tools.FileoutputUtil;
import tools.data.MaplePacketLittleEndianWriter;
import tools.packet.CField;
import tools.packet.CWvsContext;
import tools.packet.CWvsContext.AlliancePacket;
import tools.packet.CWvsContext.GuildPacket;
import tools.packet.CWvsContext.InfoPacket;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MapleGuild implements java.io.Serializable {

    private static enum BCOp {

        NONE, DISBAND, EMBELMCHANGE
    }

    public static final long serialVersionUID = 6322150443228168192L;
    private final List<MapleGuildCharacter> members = new CopyOnWriteArrayList<>();
    private final Map<Integer, MapleGuildSkill> guildSkills = new HashMap<>();
    private final String rankTitles[] = new String[5]; // 1 = master, 2 = jr, 5 = lowest member
    private String name, notice;
    private int id, gp, logo, logoColor, leader, capacity, logoBG, logoBGColor, signature, level;
    private boolean bDirty = true, proper = true;
    private int allianceid = 0, invitedid = 0;
    private final Map<Integer, MapleBBSThread> bbs = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean init = false, changed = false, changed_skills = false;
    private List<Integer> joinList = new ArrayList<>();

    public MapleGuild(final int guildid) {
        this(guildid, null);
    }

    public MapleGuild(final int guildid, Map<Integer, Map<Integer, MapleBBSReply>> replies) {
        super();

        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM questinfo WHERE customData = ?")) {
                ps.setString(1, "GuildID=" + guildid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        addJoinList(rs.getInt("characterid"));
                    }
                }
            }

            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM guilds WHERE guildid = ?")) {
                ps.setInt(1, guildid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.first()) {
                        rs.close();
                        ps.close();
                        id = -1;
                        return;
                    }
                    id = guildid;
                    name = rs.getString("name");
                    gp = rs.getInt("GP");
                    logo = rs.getInt("logo");
                    logoColor = rs.getInt("logoColor");
                    logoBG = rs.getInt("logoBG");
                    logoBGColor = rs.getInt("logoBGColor");
                    capacity = rs.getInt("capacity");
                    rankTitles[0] = rs.getString("rank1title");
                    rankTitles[1] = rs.getString("rank2title");
                    rankTitles[2] = rs.getString("rank3title");
                    rankTitles[3] = rs.getString("rank4title");
                    rankTitles[4] = rs.getString("rank5title");
                    leader = rs.getInt("leader");
                    notice = rs.getString("notice");
                    signature = rs.getInt("signature");
                    allianceid = rs.getInt("alliance");
                }
            }

            MapleGuildAlliance alliance = World.Alliance.getAlliance(allianceid);
            if (alliance == null) {
                allianceid = 0;
            }

            boolean leaderCheck = false;
            byte gFix = 0, aFix = 0;

            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT id, name, level, job, subcategory, guildrank, guildContribution, alliancerank FROM characters WHERE guildid = ? ORDER BY guildrank ASC, name ASC",
                    ResultSet.CONCUR_UPDATABLE)) {
                ps.setInt(1, guildid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.first()) {
                        System.err.println("公會ID：" + id + " 沒有成員，系統自動解散公會。");
                        rs.close();
                        ps.close();
                        writeToDB(true);
                        proper = false;
                        return;
                    }
                    do {
                        int cid = rs.getInt("id");
                        byte gRank = rs.getByte("guildrank"), aRank = rs.getByte("alliancerank");

                        if (cid == leader) {
                            leaderCheck = true;
                            if (gRank != 1) { // needs updating to 1
                                gRank = 1;
                                gFix = 1;
                            }
                            if (alliance != null) {
                                if (alliance.getLeaderId() == cid && aRank != 1) {
                                    aRank = 1;
                                    aFix = 1;
                                } else if (alliance.getLeaderId() != cid && aRank != 2) {
                                    aRank = 2;
                                    aFix = 2;
                                }
                            }
                        } else {
                            if (gRank == 1) {
                                gRank = 2;
                                gFix = 2;
                            }
                            if (aRank < 3) {
                                aRank = 3;
                                aFix = 3;
                            }
                        }
                        members.add(new MapleGuildCharacter(cid, rs.getShort("level"), rs.getString("name"), (byte) -1,
                                rs.getInt("job"), rs.getInt("subcategory"), gRank, rs.getInt("guildContribution"),
                                aRank, guildid, false));
                    } while (rs.next());
                }
            }
            if (!leaderCheck) {
                System.err.println("會長[" + leader + "]沒有在公會ID為" + id + "的公會中，系統自動解散這個公會。");
                writeToDB(true);
                proper = false;
                return;
            }
            if (gFix > 0) {
                try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET guildrank = ? WHERE id = ?")) {
                    ps.setByte(1, gFix);
                    ps.setInt(2, leader);
                    ps.executeUpdate();
                }
            }
            if (aFix > 0) {
                try (PreparedStatement ps = con
                        .prepareStatement("UPDATE characters SET alliancerank = ? WHERE id = ?")) {
                    ps.setByte(1, aFix);
                    ps.setInt(2, leader);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = con
                    .prepareStatement("SELECT * FROM bbs_threads WHERE guildid = ? ORDER BY localthreadid DESC")) {
                ps.setInt(1, guildid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final int tID = rs.getInt("localthreadid");
                        final MapleBBSThread thread = new MapleBBSThread(tID, rs.getString("name"),
                                rs.getString("startpost"), rs.getLong("timestamp"), guildid, rs.getInt("postercid"),
                                rs.getInt("icon"));
                        if (replies != null && replies.containsKey(rs.getInt("threadid"))) {
                            thread.replies.putAll(replies.get(rs.getInt("threadid")));
                        }

                        bbs.put(tID, thread);
                    }
                }
            }

            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM guildskills WHERE guildid = ?")) {
                ps.setInt(1, guildid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int sid = rs.getInt("skillid");
                        if (sid < 91000000) { // hack
                            rs.close();
                            ps.close();
                            System.err.println("非公會技能ID：" + sid + "在公會ID為" + id + "的公會中，系統自動解散該公會。");
                            writeToDB(true);
                            proper = false;
                            return;
                        }
                        guildSkills.put(sid, new MapleGuildSkill(sid, rs.getInt("level"), rs.getLong("timestamp"),
                                rs.getString("purchaser"), "")); // activators not saved
                    }
                }
            }
            level = calculateLevel();
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException se) {
            System.err.println("[MapleGuild] 從數據庫中加載公會訊息出錯" + se.getMessage());
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, se);
        }
    }

    public boolean isProper() {
        return proper;
    }

    public static final void loadAll() {
        Map<Integer, Map<Integer, MapleBBSReply>> replies = new LinkedHashMap<>();
        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM bbs_replies");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final int tID = rs.getInt("threadid");
                    Map<Integer, MapleBBSReply> reply = replies.get(tID);
                    if (reply == null) {
                        reply = new HashMap<>();
                        replies.put(tID, reply);
                    }
                    reply.put(reply.size(), new MapleBBSReply(reply.size(), rs.getInt("postercid"),
                            rs.getString("content"), rs.getLong("timestamp")));
                }
            }
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException se) {
            System.err.println("[MapleGuild.loadAll():1] 從數據庫中加載公會訊息出錯" + se.getMessage());
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, se);
        }

        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (PreparedStatement ps = con.prepareStatement("SELECT guildid FROM guilds");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    World.Guild.addLoadedGuild(new MapleGuild(rs.getInt("guildid"), replies));
                }
            }
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException se) {
            System.err.println("[MapleGuild.loadAll():2] 從數據庫中加載公會訊息出錯" + se.getMessage());
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, se);
        }

    }

    public static final void loadAll(Object toNotify) {
        Map<Integer, Map<Integer, MapleBBSReply>> replies = new LinkedHashMap<>();

        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM bbs_replies");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final int tID = rs.getInt("threadid");
                    Map<Integer, MapleBBSReply> reply = replies.get(tID);
                    if (reply == null) {
                        reply = new HashMap<>();
                        replies.put(tID, reply);
                    }
                    reply.put(reply.size(), new MapleBBSReply(reply.size(), rs.getInt("postercid"),
                            rs.getString("content"), rs.getLong("timestamp")));
                }
            }
            boolean cont = false;
            try (PreparedStatement ps = con.prepareStatement("SELECT guildid FROM guilds");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GuildLoad.QueueGuildForLoad(rs.getInt("guildid"), replies);
                    cont = true;
                }
            }
            if (!cont) {
                return;
            }
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException se) {
            System.err.println("[MapleGuild.loadAll(object)] 從數據庫中加載公會訊息出錯" + se.getMessage());
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, se);
        }
        AtomicInteger FinishedThreads = new AtomicInteger(0);
        GuildLoad.Execute(toNotify);
        synchronized (toNotify) {
            try {
                toNotify.wait();
            } catch (InterruptedException ex) {
            }
        }
        while (FinishedThreads.incrementAndGet() != GuildLoad.NumSavingThreads) {
            synchronized (toNotify) {
                try {
                    toNotify.wait();
                } catch (InterruptedException ex) {
                }
            }
        }
    }

    public final void writeToDB(final boolean bDisband) {
        try {
            Connection con = ManagerDatabasePool.getConnection();
            if (!bDisband) {
                StringBuilder buf = new StringBuilder(
                        "UPDATE guilds SET GP = ?, logo = ?, logoColor = ?, logoBG = ?, logoBGColor = ?, ");
                for (int i = 1; i < 6; i++) {
                    buf.append("rank").append(i).append("title = ?, ");
                }
                buf.append("capacity = ?, notice = ?, alliance = ?, leader = ? WHERE guildid = ?");

                PreparedStatement ps = con.prepareStatement(buf.toString());
                ps.setInt(1, gp);
                ps.setInt(2, logo);
                ps.setInt(3, logoColor);
                ps.setInt(4, logoBG);
                ps.setInt(5, logoBGColor);
                ps.setString(6, rankTitles[0]);
                ps.setString(7, rankTitles[1]);
                ps.setString(8, rankTitles[2]);
                ps.setString(9, rankTitles[3]);
                ps.setString(10, rankTitles[4]);
                ps.setInt(11, capacity);
                ps.setString(12, notice);
                ps.setInt(13, allianceid);
                ps.setInt(14, leader);
                ps.setInt(15, id);
                ps.executeUpdate();
                ps.close();

                if (changed) {
                    ps = con.prepareStatement("DELETE FROM bbs_threads WHERE guildid = ?");
                    ps.setInt(1, id);
                    ps.execute();
                    ps.close();

                    ps = con.prepareStatement("DELETE FROM bbs_replies WHERE guildid = ?");
                    ps.setInt(1, id);
                    ps.execute();
                    ps.close();
                    try (PreparedStatement pse = con.prepareStatement(
                            "INSERT INTO bbs_replies (`threadid`, `postercid`, `timestamp`, `content`, `guildid`) VALUES (?, ?, ?, ?, ?)")) {
                        ps = con.prepareStatement(
                                "INSERT INTO bbs_threads(`postercid`, `name`, `timestamp`, `icon`, `startpost`, `guildid`, `localthreadid`) VALUES(?, ?, ?, ?, ?, ?, ?)",
                                ManagerDatabasePool.RETURN_GENERATED_KEYS);
                        ps.setInt(6, id);
                        for (MapleBBSThread bb : bbs.values()) {
                            ps.setInt(1, bb.ownerID);
                            ps.setString(2, bb.name);
                            ps.setLong(3, bb.timestamp);
                            ps.setInt(4, bb.icon);
                            ps.setString(5, bb.text);
                            ps.setInt(7, bb.localthreadID);
                            ps.execute();
                            final int ourId;
                            try (ResultSet rs = ps.getGeneratedKeys()) {
                                if (!rs.next()) {
                                    rs.close();
                                    continue;
                                }
                                ourId = rs.getInt(1);
                            }
                            pse.setInt(5, id);
                            for (MapleBBSReply r : bb.replies.values()) {
                                pse.setInt(1, ourId);
                                pse.setInt(2, r.ownerID);
                                pse.setLong(3, r.timestamp);
                                pse.setString(4, r.content);
                                pse.addBatch();
                            }
                        }
                        pse.executeBatch();
                    }
                    ps.close();
                }
                if (changed_skills) {
                    ps = con.prepareStatement("DELETE FROM guildskills WHERE guildid = ?");
                    ps.setInt(1, id);
                    ps.execute();
                    ps.close();

                    ps = con.prepareStatement(
                            "INSERT INTO guildskills(`guildid`, `skillid`, `level`, `timestamp`, `purchaser`) VALUES(?, ?, ?, ?, ?)");
                    ps.setInt(1, id);
                    for (MapleGuildSkill i : guildSkills.values()) {
                        ps.setInt(2, i.skillID);
                        ps.setByte(3, (byte) i.level);
                        ps.setLong(4, i.timestamp);
                        ps.setString(5, i.purchaser);
                        ps.execute();
                    }
                    ps.close();
                }
                changed_skills = false;
                changed = false;
            } else {
                PreparedStatement ps = con.prepareStatement("DELETE FROM bbs_threads WHERE guildid = ?");
                ps.setInt(1, id);
                ps.execute();
                ps.close();

                ps = con.prepareStatement("DELETE FROM bbs_replies WHERE guildid = ?");
                ps.setInt(1, id);
                ps.execute();
                ps.close();

                ps = con.prepareStatement("DELETE FROM guildskills WHERE guildid = ?");
                ps.setInt(1, id);
                ps.execute();
                ps.close();

                ps = con.prepareStatement("DELETE FROM guilds WHERE guildid = ?");
                ps.setInt(1, id);
                ps.executeUpdate();
                ps.close();

                if (allianceid > 0) {
                    final MapleGuildAlliance alliance = World.Alliance.getAlliance(allianceid);
                    if (alliance != null) {
                        alliance.removeGuild(id, false);
                    }
                }

                broadcast(GuildPacket.guildDisband(id));
            }
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException se) {
            System.err.println("[MapleGuild] 儲存公會訊息出錯");
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, se);
        }
    }

    public final int getId() {
        return id;
    }

    public final int getLeaderId() {
        return leader;
    }

    public final MapleCharacter getLeader(final MapleClient c) {
        return c.getChannelServer().getPlayerStorage().getCharacterById(leader);
    }

    public final int getGP() {
        return gp;
    }

    public final int getLogo() {
        return logo;
    }

    public final void setLogo(final int l) {
        logo = l;
    }

    public final int getLogoColor() {
        return logoColor;
    }

    public final void setLogoColor(final int c) {
        logoColor = c;
    }

    public final int getLogoBG() {
        return logoBG;
    }

    public final void setLogoBG(final int bg) {
        logoBG = bg;
    }

    public final int getLogoBGColor() {
        return logoBGColor;
    }

    public final void setLogoBGColor(final int c) {
        logoBGColor = c;
    }

    public final String getNotice() {
        if (notice == null) {
            return "";
        }
        return notice;
    }

    public final String getName() {
        return name;
    }

    public final int getCapacity() {
        return capacity;
    }

    public final int getSignature() {
        return signature;
    }

    public final void broadcast(final byte[] packet) {
        broadcast(packet, -1, BCOp.NONE);
    }

    public final void broadcast(final byte[] packet, final int exception) {
        broadcast(packet, exception, BCOp.NONE);
    }

    // multi-purpose function that reaches every member of guild (except the
    // character with exceptionId) in all channels with as little access to rmi as
    // possible
    public final void broadcast(final byte[] packet, final int exceptionId, final BCOp bcop) {
        lock.writeLock().lock();
        try {
            buildNotifications();
        } finally {
            lock.writeLock().unlock();
        }

        lock.readLock().lock();
        try {
            for (MapleGuildCharacter mgc : members) {
                if (bcop == BCOp.DISBAND) {
                    if (mgc.isOnline()) {
                        World.Guild.setGuildAndRank(mgc.getId(), 0, 5, 0, 5);
                    } else {
                        setOfflineGuildStatus(0, (byte) 5, 0, (byte) 5, mgc.getId());
                    }
                } else if (mgc.isOnline() && mgc.getId() != exceptionId) {
                    if (bcop == BCOp.EMBELMCHANGE) {
                        World.Guild.changeEmblem(id, mgc.getId(), this);
                    } else {
                        World.Broadcast.sendGuildPacket(mgc.getId(), packet, exceptionId, id);
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }

    }

    private void buildNotifications() {
        if (!bDirty) {
            return;
        }
        final List<Integer> mem = new LinkedList<>();
        final Iterator<MapleGuildCharacter> toRemove = members.iterator();
        while (toRemove.hasNext()) {
            MapleGuildCharacter mgc = toRemove.next();
            if (!mgc.isOnline()) {
                continue;
            }
            if (mem.contains(mgc.getId()) || mgc.getGuildId() != id) {
                members.remove(mgc);
                continue;
            }
            mem.add(mgc.getId());

        }
        bDirty = false;
    }

    public final void setOnline(final int cid, final boolean online, final int channel) {
        boolean bBroadcast = true;
        for (MapleGuildCharacter mgc : members) {
            if (mgc.getGuildId() == id && mgc.getId() == cid) {
                if (mgc.isOnline() == online) {
                    bBroadcast = false;
                }
                mgc.setOnline(online);
                mgc.setChannel((byte) channel);
                if (bBroadcast) {
                    broadcast(GuildPacket.guildMemberOnline(mgc), cid);
                    if (allianceid > 0) {
                        World.Alliance.sendGuild(AlliancePacket.allianceMemberOnline(allianceid, id, cid, online), id,
                                allianceid);
                    }
                }
                break;
            }
        }
        bDirty = true; // member formation has changed, update notifications
        init = true;
    }

    public final void guildChat(final String name, final int cid, final String msg) {
        broadcast(CField.multiChat(name, msg, 2), cid);
    }

    public final void allianceChat(final String name, final int cid, final String msg) {
        broadcast(CField.multiChat(name, msg, 3), cid);
    }

    public final String getRankTitle(final int rank) {
        return rankTitles[rank - 1];
    }

    public int getAllianceId() {
        // return alliance.getId();
        return this.allianceid;
    }

    public int getInvitedId() {
        return this.invitedid;
    }

    public void setInvitedId(int iid) {
        this.invitedid = iid;
    }

    public void setAllianceId(int a) {
        this.allianceid = a;
        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (PreparedStatement ps = con.prepareStatement("UPDATE guilds SET alliance = ? WHERE guildid = ?")) {
                ps.setInt(1, a);
                ps.setInt(2, id);
                ps.execute();
            }
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException e) {
            System.err.println("Saving allianceid ERROR" + e);
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, e);
        }
    }

    // function to create guild, returns the guild id if successful, 0 if not
    public static final int createGuild(final int leaderId, final String name) {
        if (name.length() > 12) {
            return 0;
        }
        try {
            int ret;
            Connection con = ManagerDatabasePool.getConnection();
            PreparedStatement ps = con.prepareStatement("SELECT guildid FROM guilds WHERE name = ?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.first()) {// name taken
                rs.close();
                ps.close();
                return 0;
            }
            ps.close();
            rs.close();
            ps = con.prepareStatement(
                    "INSERT INTO guilds (`leader`, `name`, `signature`, `alliance`) VALUES (?, ?, ?, 0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, leaderId);
            ps.setString(2, name);
            ps.setInt(3, (int) (System.currentTimeMillis() / 1000));
            ps.executeUpdate();
            rs = ps.getGeneratedKeys();
            ret = 0;
            if (rs.next()) {
                ret = rs.getInt(1);
            }
            rs.close();
            ps.close();
            ManagerDatabasePool.closeConnection(con);
            return ret;
        } catch (SQLException se) {
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, se);
            return 0;
        }
    }

    public final int addGuildMember(final MapleGuildCharacter mgc) {
        // first of all, insert it into the members keeping alphabetical order of lowest
        // ranks ;)
        lock.writeLock().lock();
        try {
            if (members.size() >= capacity) {
                return 0;
            }
            for (int i = members.size() - 1; i >= 0; i--) {
                if (members.get(i).getGuildRank() < 5 || members.get(i).getName().compareTo(mgc.getName()) < 0) {
                    members.add(i + 1, mgc);
                    bDirty = true;
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        gainGP(500, true, mgc.getId());
        setGuildQuest(false, MapleCharacter.getCharacterById(mgc.getId()));
        broadcast(GuildPacket.newGuildMember(mgc));
        if (allianceid > 0) {
            World.Alliance.sendGuild(allianceid);
        }
        return 1;
    }

    public final int addGuildJoinMember(final MapleGuildCharacter mgc) {
        lock.writeLock().lock();
        try {
            if (members.size() >= capacity) {
                return 0;
            }
        } finally {
            lock.writeLock().unlock();
        }
        setGuildQuest(true, MapleCharacter.getCharacterById(mgc.getId()));
        broadcast(GuildPacket.newGuildJoinMember(mgc));
        return 1;
    }

    public final int removeGuildJoinMember(final int cid) {
        setGuildQuest(false, MapleCharacter.getCharacterById(cid));
        broadcast(GuildPacket.removeGuildJoin(cid));
        return 1;
    }

    public final void leaveGuild(final MapleGuildCharacter mgc) {
        lock.writeLock().lock();
        try {
            final Iterator<MapleGuildCharacter> itr = members.iterator();
            while (itr.hasNext()) {
                final MapleGuildCharacter mgcc = itr.next();

                if (mgcc.getId() == mgc.getId()) {
                    broadcast(GuildPacket.memberLeft(mgcc, true));
                    bDirty = true;
                    gainGP(mgcc.getGuildContribution() > 0 ? -mgcc.getGuildContribution() : -50);
                    members.remove(mgcc);
                    if (mgc.isOnline()) {
                        World.Guild.setGuildAndRank(mgcc.getId(), 0, 5, 0, 5);
                    } else {
                        setOfflineGuildStatus((short) 0, (byte) 5, 0, (byte) 5, mgcc.getId());
                    }
                    break;
                }
            }

        } finally {
            lock.writeLock().unlock();
        }
        if (bDirty && allianceid > 0) {
            World.Alliance.sendGuild(allianceid);
        }
    }

    public final void expelMember(final MapleGuildCharacter initiator, final String name, final int cid) {
        lock.writeLock().lock();
        try {
            for (MapleGuildCharacter mgc : members) {
                if (mgc.getId() == cid && initiator.getGuildRank() < mgc.getGuildRank()) {
                    broadcast(GuildPacket.memberLeft(mgc, true));

                    bDirty = true;

                    gainGP(mgc.getGuildContribution() > 0 ? -mgc.getGuildContribution() : -50);
                    if (mgc.isOnline()) {
                        World.Guild.setGuildAndRank(cid, 0, 5, 0, 5);
                    } else {
                        MapleCharacterUtil.sendNote(mgc.getName(), initiator.getName(), "被公會除名了。", 0);
                        setOfflineGuildStatus((short) 0, (byte) 5, 0, (byte) 5, cid);
                    }
                    members.remove(mgc);
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (bDirty && allianceid > 0) {
            World.Alliance.sendGuild(allianceid);
        }
    }

    public final void changeARank() {
        changeARank(false);
    }

    public final void changeARank(final boolean leader) {
        if (allianceid <= 0) {
            return;
        }
        for (final MapleGuildCharacter mgc : members) {
            byte newRank = 3;
            if (this.leader == mgc.getId()) {
                newRank = (byte) (leader ? 1 : 2);
            }
            if (mgc.isOnline()) {
                World.Guild.setGuildAndRank(mgc.getId(), this.id, mgc.getGuildRank(), mgc.getGuildContribution(),
                        newRank);
            } else {
                setOfflineGuildStatus(this.id, mgc.getGuildRank(), mgc.getGuildContribution(), newRank, mgc.getId());
            }
            mgc.setAllianceRank(newRank);
        }
        World.Alliance.sendGuild(allianceid);
    }

    public final void changeARank(final int newRank) {
        if (allianceid <= 0) {
            return;
        }
        for (final MapleGuildCharacter mgc : members) {
            if (mgc.isOnline()) {
                World.Guild.setGuildAndRank(mgc.getId(), this.id, mgc.getGuildRank(), mgc.getGuildContribution(),
                        newRank);
            } else {
                setOfflineGuildStatus(this.id, mgc.getGuildRank(), mgc.getGuildContribution(), (byte) newRank,
                        mgc.getId());
            }
            mgc.setAllianceRank((byte) newRank);
        }
        World.Alliance.sendGuild(allianceid);
    }

    public final boolean changeARank(final int cid, final int newRank) {
        if (allianceid <= 0) {
            return false;
        }
        for (final MapleGuildCharacter mgc : members) {
            if (cid == mgc.getId()) {
                if (mgc.isOnline()) {
                    World.Guild.setGuildAndRank(cid, this.id, mgc.getGuildRank(), mgc.getGuildContribution(), newRank);
                } else {
                    setOfflineGuildStatus(this.id, mgc.getGuildRank(), mgc.getGuildContribution(), (byte) newRank, cid);
                }
                mgc.setAllianceRank((byte) newRank);
                World.Alliance.sendGuild(allianceid);
                return true;
            }
        }
        return false;
    }

    public final void changeGuildLeader(final int cid) {
        if (changeRank(cid, 1) && changeRank(leader, 2)) {
            MapleGuildCharacter dwCharacter = getMGC(leader);
            if (allianceid > 0) {
                int aRank = dwCharacter.getAllianceRank();
                if (aRank == 1) {
                    World.Alliance.changeAllianceLeader(allianceid, cid, true);
                } else {
                    changeARank(cid, aRank);
                }
                changeARank(leader, 3);
            }
            this.leader = cid;
            broadcast(GuildPacket.guildLeaderChanged(this, dwCharacter));
            try {
                Connection con = ManagerDatabasePool.getConnection();
                try (PreparedStatement ps = con.prepareStatement("UPDATE guilds SET leader = ? WHERE guildid = ?")) {
                    ps.setInt(1, cid);
                    ps.setInt(2, id);
                    ps.execute();
                }
                ManagerDatabasePool.closeConnection(con);
            } catch (SQLException e) {
                System.err.println("Saving leaderid ERROR" + e);
                FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, e);
            }
        }
    }

    public final boolean changeRank(final int cid, final int newRank) {
        for (final MapleGuildCharacter mgc : members) {
            if (cid == mgc.getId()) {
                if (mgc.isOnline()) {
                    World.Guild.setGuildAndRank(cid, this.id, newRank, mgc.getGuildContribution(),
                            mgc.getAllianceRank());
                } else {
                    setOfflineGuildStatus(this.id, (byte) newRank, mgc.getGuildContribution(), mgc.getAllianceRank(),
                            cid);
                }
                mgc.setGuildRank((byte) newRank);
                broadcast(GuildPacket.changeRank(mgc));
                return true;
            }
        }
        // it should never get to this point unless cid was incorrect o_O
        return false;
    }

    public final void setGuildNotice(final String notice) {
        this.notice = notice;
        broadcast(GuildPacket.guildNotice(this));
    }

    public final void memberLevelJobUpdate(final MapleGuildCharacter mgc) {
        for (final MapleGuildCharacter member : members) {
            if (member.getId() == mgc.getId()) {
                int old_level = member.getLevel();
                int old_job = member.getJobCode();
                member.setJobCode(mgc.getJobCode());
                member.setLevel((short) mgc.getLevel());
                if (mgc.getLevel() > old_level) {
                    gainGP((mgc.getLevel() - old_level) * mgc.getLevel(), false, mgc.getId());
                    // aftershock: formula changes (below 100 = 40, above 100 = 80) (12000 max) but
                    // i prefer level (21100 max), add guildContribution, do setGuildAndRank or just
                    // get the MapleCharacter object
                }
                if (old_level != mgc.getLevel()) {
                    this.broadcast(CWvsContext.sendLevelup(false, mgc.getLevel(), mgc.getName()), mgc.getId());
                }
                if (old_job != mgc.getJobCode()) {
                    this.broadcast(CWvsContext.sendJobup(false, mgc.getJobCode(), mgc.getName()), mgc.getId());
                }
                broadcast(GuildPacket.guildMemberLevelJobUpdate(mgc));
                if (allianceid > 0) {
                    World.Alliance.sendGuild(AlliancePacket.updateAlliance(mgc, allianceid), id, allianceid);
                }
                break;
            }
        }
    }

    public final void changeRankTitle(final String[] ranks) {
        System.arraycopy(ranks, 0, rankTitles, 0, 5);
        broadcast(GuildPacket.rankTitleChange(this));
    }

    public final void disbandGuild() {
        writeToDB(true);
        broadcast(null, -1, BCOp.DISBAND);
    }

    public final void setGuildEmblem(final short bg, final byte bgcolor, final short logo, final byte logocolor) {
        this.logoBG = bg;
        this.logoBGColor = bgcolor;
        this.logo = logo;
        this.logoColor = logocolor;
        broadcast(null, -1, BCOp.EMBELMCHANGE);

        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE guilds SET logo = ?, logoColor = ?, logoBG = ?, logoBGColor = ? WHERE guildid = ?")) {
                ps.setInt(1, logo);
                ps.setInt(2, logoColor);
                ps.setInt(3, logoBG);
                ps.setInt(4, logoBGColor);
                ps.setInt(5, id);
                ps.execute();
            }
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException e) {
            System.err.println("Saving guild logo / BG colo ERROR");
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, e);
        }
    }

    public final MapleGuildCharacter getMGC(final int cid) {
        for (final MapleGuildCharacter mgc : members) {
            if (mgc.getId() == cid) {
                return mgc;
            }
        }
        return null;
    }

    public final boolean increaseCapacity(boolean trueMax) {
        if (capacity >= (trueMax ? 200 : 100) || ((capacity + 5) > (trueMax ? 200 : 100))) {
            return false;
        }
        if (trueMax && gp < 25000) {
            return false;
        }
        if (trueMax && gp - 25000 < GameConstants.getGuildExpNeededForLevel(getLevel() - 1)) {
            return false;
        }
        capacity += 5;
        broadcast(GuildPacket.guildCapacityChange(this));

        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (PreparedStatement ps = con.prepareStatement("UPDATE guilds SET capacity = ? WHERE guildid = ?")) {
                ps.setInt(1, this.capacity);
                ps.setInt(2, this.id);
                ps.execute();
            }
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException e) {
            System.err.println("Saving guild capacity ERROR");
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, e);
        }
        return true;
    }

    public final void gainGP(final int amount) {
        gainGP(amount, true, -1);
    }

    public final void gainGP(int amount, final boolean broadcast) {
        gainGP(amount, broadcast, -1);
    }

    public final void gainGP(int amount, final boolean broadcast, final int cid) {
        if (amount == 0) { // no change, no broadcast and no sql.
            return;
        }
        if (amount + gp < 0) {
            amount = -gp;
        } // 0 lowest
        if (cid > 0 && amount > 0) {
            final MapleGuildCharacter mg = getMGC(cid);
            if (mg != null) {
                mg.setGuildContribution(mg.getGuildContribution() + amount);
                if (mg.isOnline()) {
                    World.Guild.setGuildAndRank(cid, this.id, mg.getGuildRank(), mg.getGuildContribution(),
                            mg.getAllianceRank());
                } else {
                    setOfflineGuildStatus(this.id, mg.getGuildRank(), mg.getGuildContribution(), mg.getAllianceRank(),
                            cid);
                }
                broadcast(GuildPacket.guildContribution(mg));
            }
        }
        gp += amount;
        level = calculateLevel();
        broadcast(GuildPacket.updateGP(this));
        if (broadcast) {
            broadcast(InfoPacket.getGPMsg(amount));
        }
    }

    public Collection<MapleGuildSkill> getSkills() {
        return guildSkills.values();
    }

    public int getSkillLevel(int sid) {
        if (!guildSkills.containsKey(sid)) {
            return 0;
        }
        return guildSkills.get(sid).level;
    }

    public boolean activateSkill(int skill, String name) {
        if (!guildSkills.containsKey(skill)) {
            return false;
        }
        final MapleGuildSkill ourSkill = guildSkills.get(skill);
        final MapleStatEffect skillid = SkillFactory.getSkill(skill).getEffect(ourSkill.level);
        if (ourSkill.timestamp > System.currentTimeMillis() || skillid.getPeriod() <= 0) {
            return false;
        }
        ourSkill.timestamp = System.currentTimeMillis() + (skillid.getPeriod() * 60000L);
        ourSkill.activator = name;
        broadcast(GuildPacket.guildSkillPurchased(this, ourSkill));
        return true;
    }

    public boolean purchaseSkill(int skill, String name, int cid) {
        final MapleStatEffect skillid = SkillFactory.getSkill(skill).getEffect(getSkillLevel(skill) + 1);
        if (skillid.getReqGuildLevel() > getLevel() || skillid.getLevel() <= getSkillLevel(skill)) {
            return false;
        }
        MapleGuildSkill ourSkill = guildSkills.get(skill);
        if (ourSkill == null) {
            ourSkill = new MapleGuildSkill(skill, skillid.getLevel(), 0, name, name);
            guildSkills.put(skill, ourSkill);
        } else {
            ourSkill.level = skillid.getLevel();
            ourSkill.purchaser = name;
            ourSkill.activator = name;
        }
        if (skillid.getPeriod() <= 0) {
            ourSkill.timestamp = -1L;
        } else {
            ourSkill.timestamp = System.currentTimeMillis() + (skillid.getPeriod() * 60000L);
        }
        changed_skills = true;
        gainGP(1000, true, cid);
        broadcast(GuildPacket.guildSkillPurchased(this, ourSkill));
        return true;
    }

    public int getLevel() {
        return level;
    }

    public final int calculateLevel() {
        for (int i = 1; i < 10; i++) {
            if (gp < GameConstants.getGuildExpNeededForLevel(i)) {
                return i;
            }
        }
        return 10;
    }

    public final void addMemberData(final MaplePacketLittleEndianWriter mplew) {
        mplew.writeShort(members.size());
        for (final MapleGuildCharacter mgc : members) {
            mplew.writeInt(mgc.getId()); // adwCharacterID
        }
        for (final MapleGuildCharacter mgc : members) {
            CWvsContext.GuildPacket.GUILDMEMBER_Decode(mplew, mgc); // aMemberData
        }
    }

    public final void addMemberForm(final MaplePacketLittleEndianWriter mplew) {
        mplew.writeShort(joinList.size());
        for (int cid : getJoinList()) {
            mplew.writeInt(cid); // adwRequestCharacterID
        }
        for (int cid : getJoinList()) {
            MapleCharacter chr = MapleCharacter.getCharacterById(cid);
            if (chr != null) {
                chr.setGuildId(id);
                CWvsContext.GuildPacket.GUILDMEMBER_Decode(mplew, chr.getMGC()); // aRequestUserData
                chr.setGuildId(0);
            }
        }
    }

    public static List<MapleGuild> searchGuild(int type, boolean equal, String keyWord) {
        List<MapleGuild> guilds = new ArrayList<>();
        for (MapleGuild guild : getAllGuilds()) {
            boolean add = false;
            switch (type) {
                case 1:
                    if (equal) {
                        add = guild.getName().equalsIgnoreCase(keyWord)
                                || guild.getMGC(guild.getLeaderId()).getName().equalsIgnoreCase(keyWord);
                    } else {
                        add = guild.getName().contains(keyWord)
                                || guild.getMGC(guild.getLeaderId()).getName().contains(keyWord);
                    }
                    break;
                case 2:
                    if (equal) {
                        add = guild.getName().equalsIgnoreCase(keyWord);
                    } else {
                        add = guild.getName().contains(keyWord);
                    }
                    break;
                case 3:
                    if (equal) {
                        add = guild.getMGC(guild.getLeaderId()).getName().equalsIgnoreCase(keyWord);
                    } else {
                        add = guild.getMGC(guild.getLeaderId()).getName().contains(keyWord);
                    }
                    break;
            }
            if (add) {
                guilds.add(guild);
            }
        }
        return guilds;
    }

    public static List<MapleGuild> searchGuild(int[] keyWords) {
        List<MapleGuild> guilds = new ArrayList<>();
        for (MapleGuild guild : getAllGuilds()) {
            int a = guild.getMembers().size();
            if (keyWords[0] <= guild.getLevel() && guild.getLevel() <= keyWords[1]
                    && keyWords[2] <= guild.getMembers().size() && guild.getMembers().size() <= keyWords[3]
                    && keyWords[4] <= guild.getAverageLevel() && guild.getMembers().size() <= keyWords[5]) {
                guilds.add(guild);
            }
        }
        return guilds;
    }

    public static List<MapleGuild> getAllGuilds() {
        List<MapleGuild> guilds = new ArrayList<>();

        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM `guilds`")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    guilds.add(World.Guild.getGuild(rs.getInt("guildid")));
                }
                rs.close();
                ps.close();
            }
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException se) {
            System.out.println("SQLException: " + se.getLocalizedMessage());
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, se);
        }
        return guilds;
    }

    // null indicates successful invitation being sent
    // keep in mind that this will be called by a handler most of the time
    // so this will be running mostly on a channel server, unlike the rest
    // of the class
    public static final GuildOpcode sendInvite(final MapleClient c, final String targetName) {
        final MapleCharacter mc = c.getChannelServer().getPlayerStorage().getCharacterByName(targetName);
        if (mc == null) {
            return GuildOpcode.GuildRes_JoinGuild_NonRequestFindUser;
        }
        if (mc.getGuildId() > 0) {
            return GuildOpcode.GuildRes_JoinGuild_AlreadyJoined;
        }
        mc.getClient().getSession().writeAndFlush(GuildPacket.guildInvite(c.getPlayer().getGuild(), c.getPlayer()));
        return null;
    }

    public java.util.Collection<MapleGuildCharacter> getMembers() {
        return java.util.Collections.unmodifiableCollection(members);
    }

    public final boolean isInit() {
        return init;
    }

    public final List<MapleBBSThread> getBBS() {
        final List<MapleBBSThread> ret = new ArrayList<>(bbs.values());
        Collections.sort(ret, new MapleBBSThread.ThreadComparator());
        return ret;
    }

    public final int addBBSThread(final String title, final String text, final int icon, final boolean bNotice,
            final int posterID) {
        final int add = bbs.get(0) == null ? 1 : 0; // add 1 if no notice
        changed = true;
        final int ret = bNotice ? 0 : Math.max(1, bbs.size() + add);
        bbs.put(ret, new MapleBBSThread(ret, title, text, System.currentTimeMillis(), this.id, posterID, icon));
        return ret;
    }

    public final void editBBSThread(final int localthreadid, final String title, final String text, final int icon,
            final int posterID, final int guildRank) {
        final MapleBBSThread thread = bbs.get(localthreadid);
        if (thread != null && (thread.ownerID == posterID || guildRank <= 2)) {
            changed = true;
            bbs.put(localthreadid, new MapleBBSThread(localthreadid, title, text, System.currentTimeMillis(), this.id,
                    thread.ownerID, icon));
        }
    }

    public final void deleteBBSThread(final int localthreadid, final int posterID, final int guildRank) {
        final MapleBBSThread thread = bbs.get(localthreadid);
        if (thread != null && (thread.ownerID == posterID || guildRank <= 2)) {
            changed = true;
            bbs.remove(localthreadid);
        }
    }

    public final void addBBSReply(final int localthreadid, final String text, final int posterID) {
        final MapleBBSThread thread = bbs.get(localthreadid);
        if (thread != null) {
            changed = true;
            thread.replies.put(thread.replies.size(),
                    new MapleBBSReply(thread.replies.size(), posterID, text, System.currentTimeMillis()));
        }
    }

    public final void deleteBBSReply(final int localthreadid, final int replyid, final int posterID,
            final int guildRank) {
        final MapleBBSThread thread = bbs.get(localthreadid);
        if (thread != null) {
            final MapleBBSReply reply = thread.replies.get(replyid);
            if (reply != null && (reply.ownerID == posterID || guildRank <= 2)) {
                changed = true;
                thread.replies.remove(replyid);
            }
        }
    }

    public boolean hasSkill(int id) {
        return guildSkills.containsKey(id);
    }

    public static void setOfflineGuildStatus(int guildid, byte guildrank, int contribution, byte alliancerank,
            int cid) {
        try {
            Connection con = ManagerDatabasePool.getConnection();
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE characters SET guildid = ?, guildrank = ?, guildContribution = ?, alliancerank = ? WHERE id = ?")) {
                ps.setInt(1, guildid);
                ps.setInt(2, guildrank);
                ps.setInt(3, contribution);
                ps.setInt(4, alliancerank);
                ps.setInt(5, cid);
                ps.executeUpdate();
            }
            ManagerDatabasePool.closeConnection(con);
        } catch (SQLException se) {
            System.out.println("SQLException: " + se.getLocalizedMessage());
            FileoutputUtil.outputFileError(FileoutputUtil.SQL_Log, se);
        }
    }

    public final int getAverageLevel() {
        int totalLevel = 0;
        for (MapleGuildCharacter mgc : members) {
            totalLevel += mgc.getLevel();
        }
        return totalLevel / members.size();
    }

    public final void setGuildQuest(boolean add, MapleCharacter chr) {
        if (add) {
            addJoinList(chr.getId());
        } else {
            removeJoinList(chr.getId());
        }
        chr.updateInfoQuest(GameConstants.申請公會名, add ? "name=" + name : "");
        chr.updateInfoQuest(GameConstants.申請公會ID, add ? "GuildID=" + id : "");
    }

    public final void addJoinList(int cid) {
        joinList.add(cid);
    }

    public final void removeJoinList(int cid) {
        Iterator<Integer> itr = joinList.iterator();
        while (itr.hasNext()) {
            if (cid == itr.next()) {
                itr.remove();
            }
        }
    }

    public static String getJoinGuildName(int cid) {
        MapleCharacter chr = MapleCharacter.getCharacterById(cid);
        String questInfo = chr.getInfoQuest(GameConstants.申請公會名);
        if (questInfo.split("=").length > 1) {
            return questInfo.split("=")[1];
        }
        return null;
    }

    public static int getJoinGuildId(int cid) {
        MapleCharacter chr = MapleCharacter.getCharacterById(cid);
        String questInfo = chr.getInfoQuest(GameConstants.申請公會ID);
        if (questInfo.split("=").length > 1) {
            return Integer.valueOf(questInfo.split("=")[1]);
        }
        return -1;
    }

    public final List<Integer> getJoinList() {
        return joinList;
    }
}
