package sangong.mode;

import com.alibaba.fastjson.JSON;
import sangong.entrance.SanGongTcpService;
import sangong.redis.RedisService;
import sangong.timout.ReadyTimeout;

import java.util.*;

/**
 * Created by pengyi
 * Date : 17-9-1.
 * desc:
 */
public class MatchInfo {

    private int status;
    private List<Integer> rooms;
    private Arena arena;
    private List<MatchUser> matchUsers;
    private boolean start;
    private List<MatchUser> waitUsers;
    private int matchEliminateScore;

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public List<Integer> getRooms() {
        return rooms;
    }

    public void setRooms(List<Integer> rooms) {
        this.rooms = rooms;
    }

    public Arena getArena() {
        return arena;
    }

    public void setArena(Arena arena) {
        this.arena = arena;
    }

    public List<MatchUser> getMatchUsers() {
        return matchUsers;
    }

    public void setMatchUsers(List<MatchUser> matchUsers) {
        this.matchUsers = matchUsers;
    }

    public boolean isStart() {
        return start;
    }

    public void setStart(boolean start) {
        this.start = start;
    }

    public List<MatchUser> getWaitUsers() {
        return waitUsers;
    }

    public void setWaitUsers(List<MatchUser> waitUsers) {
        this.waitUsers = waitUsers;
    }

    public int getMatchEliminateScore() {
        return matchEliminateScore;
    }

    public void setMatchEliminateScore(int matchEliminateScore) {
        this.matchEliminateScore = matchEliminateScore;
    }

    public int addRoom(String matchNo, int gameTimes, RedisService redisService, List<User> users, Map<Integer, Integer> userIdScore,
                       GameBase.BaseConnection.Builder response, GameBase.MatchData.Builder matchData) {
        Room room = new Room();
        room.setBaseScore(1);
        room.setRoomNo(roomNo(redisService));
        room.setGameTimes(gameTimes);
        room.setCount(4);
        room.setBankerWay(1);
        room.setGameStatus(GameStatus.WAITING);
        room.setSeatNos(new ArrayList<>(Arrays.asList(1, 2, 3, 4)));
        GameBase.MatchResult.Builder matchResult = GameBase.MatchResult.newBuilder();
        while (4 > room.getSeats().size()) {
            User user = users.remove(0);
            room.addSeat(user, userIdScore.get(user.getUserId()));
            matchResult.setResult(1).setTotalScore(userIdScore.get(user.getUserId())).setCurrentScore(-1);
            response.setOperationType(GameBase.OperationType.MATCH_RESULT).setData(matchResult.build().toByteString());
            if (SanGongTcpService.userClients.containsKey(user.getUserId())) {
                SanGongTcpService.userClients.get(user.getUserId()).send(response.build(), user.getUserId());
                room.sendRoomInfo(user.getUserId(), GameBase.RoomCardIntoResponse.newBuilder(), response);
            }
            redisService.addCache("room_match" + room.getRoomNo(), matchNo);
            redisService.addCache("reconnect" + user.getUserId(), "sangong," + room.getRoomNo());
        }
        room.sendSeatInfo(response);

        for (Seat seat : room.getSeats()) {
            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                SanGongTcpService.userClients.get(seat.getUserId()).roomNo = room.getRoomNo();
                response.setOperationType(GameBase.OperationType.MATCH_DATA).setData(matchData.build().toByteString());
                SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
            }
        }

        new ReadyTimeout(Integer.parseInt(room.getRoomNo()), redisService, 0).start();
        redisService.addCache("room" + room.getRoomNo(), JSON.toJSONString(room));
        return Integer.parseInt(room.getRoomNo());
    }

    /**
     * 生成随机桌号
     *
     * @return 桌号
     */
    private String roomNo(RedisService redisService) {
        String roomNo = (new Random().nextInt(899999) + 100001) + "";
        if (redisService.exists("room" + roomNo + "")) {
            roomNo = roomNo(redisService);
        }
        return roomNo;
    }
}
