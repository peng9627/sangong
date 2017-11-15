package sangong.entrance;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sangong.constant.Constant;
import sangong.mode.*;
import sangong.redis.RedisService;
import sangong.timout.DissolveTimeout;
import sangong.timout.MatchScoreTimeout;
import sangong.timout.ReadyTimeout;
import sangong.utils.HttpUtil;
import sangong.utils.LoggerUtil;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Created date 2016/3/25
 * Author pengyi
 */
public class SanGongClient {

    private Logger logger = LoggerFactory.getLogger(this.getClass());
    public int userId;
    private RedisService redisService;

    private GameBase.BaseConnection.Builder response;
    private MessageReceive messageReceive;

    SanGongClient(RedisService redisService, MessageReceive messageReceive) {
        this.redisService = redisService;
        this.messageReceive = messageReceive;
        this.response = GameBase.BaseConnection.newBuilder();
    }

    public void close() {
        if (0 != userId) {
            synchronized (SanGongTcpService.userClients) {
                if (SanGongTcpService.userClients.containsKey(userId) && messageReceive == SanGongTcpService.userClients.get(userId)) {
                    SanGongTcpService.userClients.remove(userId);
                    if (redisService.exists("room" + messageReceive.roomNo)) {
                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
                        if (null == room) {
                            return;
                        }
                        for (Seat seat : room.getSeats()) {
                            if (seat.getUserId() == userId && !seat.isRobot()) {
                                seat.setRobot(true);
                                response.setOperationType(GameBase.OperationType.ONLINE).setData(GameBase.Online.newBuilder()
                                        .setOnline(false).setUserId(userId).build().toByteString());
                                for (Seat seat1 : room.getSeats()) {
                                    if (SanGongTcpService.userClients.containsKey(seat1.getUserId())) {
                                        messageReceive.send(response.build(), seat1.getUserId());
                                    }
                                }
                                break;
                            }
                        }

                        redisService.addCache("room" + messageReceive.roomNo, JSON.toJSONString(room));
                        redisService.unlock("lock_room" + messageReceive.roomNo);
                    }
                }
            }
        }
    }

    private void addSeat(Room room, SanGong.SangongGameInfo.Builder gameInfo) {
        for (Seat seat1 : room.getSeats()) {
            SanGong.SangongSeatGameInfo.Builder seatResponse = SanGong.SangongSeatGameInfo.newBuilder();
            seatResponse.setID(seat1.getUserId());
            seatResponse.setScore(seat1.getScore());
            seatResponse.setOpen(seat1.isOpen());
            seatResponse.setPlayScore(seat1.getPlayScore());
            if (null != seat1.getCards() && 0 != seat1.getCards().size()) {
                if (seat1.getUserId() == userId) {
                    seatResponse.addAllCards(seat1.getCards());
                } else {
                    if (seat1.isOpen()) {
                        seatResponse.addAllCards(seat1.getCards());
                    } else {
                        seatResponse.addCards(0);
                        seatResponse.addCards(0);
                        seatResponse.addCards(0);
                    }
                }
            }
            gameInfo.addSeats(seatResponse.build());
        }
    }

    synchronized void receive(GameBase.BaseConnection request) {
        try {
            logger.info("接收" + userId + request.getOperationType().toString());
            switch (request.getOperationType()) {
                case HEARTBEAT:
                    messageReceive.send(response.setOperationType(GameBase.OperationType.HEARTBEAT).clearData().build(), userId);
                    break;
                case CONNECTION:
                    //加入玩家数据
                    if (redisService.exists("maintenance")) {
                        break;
                    }
                    GameBase.RoomCardIntoRequest intoRequest = GameBase.RoomCardIntoRequest.parseFrom(request.getData());
                    userId = intoRequest.getID();
                    messageReceive.roomNo = intoRequest.getRoomNo();
                    if (SanGongTcpService.userClients.containsKey(userId) && SanGongTcpService.userClients.get(userId) != messageReceive) {
                        SanGongTcpService.userClients.get(userId).close();
                    }
                    synchronized (this) {
                        try {
                            wait(10);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("userId", userId);
                    ApiResponse<User> userResponse = JSON.parseObject(HttpUtil.urlConnectionByRsa(Constant.apiUrl + Constant.userInfoUrl, jsonObject.toJSONString()), new TypeReference<ApiResponse<User>>() {
                    });
                    if (0 == userResponse.getCode()) {
                        SanGongTcpService.userClients.put(userId, messageReceive);
                        GameBase.RoomCardIntoResponse.Builder intoResponseBuilder = GameBase.RoomCardIntoResponse.newBuilder();
                        intoResponseBuilder.setGameType(GameBase.GameType.SANGONG).setRoomNo(messageReceive.roomNo);
                        if (redisService.exists("room" + messageReceive.roomNo)) {
                            while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                            }

                            redisService.addCache("reconnect" + userId, "sangong," + messageReceive.roomNo);

                            Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
                            intoResponseBuilder.setRoomOwner(room.getRoomOwner());
                            intoResponseBuilder.setStarted(0 != room.getGameStatus().compareTo(GameStatus.READYING) && 0 != room.getGameStatus().compareTo(GameStatus.WAITING));
                            if (0 == room.getGameStatus().compareTo(GameStatus.READYING) && redisService.exists("room_match" + messageReceive.roomNo)) {
                                int time = 8 - (int) ((new Date().getTime() - room.getStartDate().getTime()) / 1000);
                                intoResponseBuilder.setReadyTimeCounter(time > 0 ? time : 0);
                            }
                            //房间是否已存在当前用户，存在则为重连
                            final boolean[] find = {false};
                            room.getSeats().stream().filter(seat -> seat.getUserId() == userId).forEach(seat -> {
                                find[0] = true;
                                seat.setRobot(false);
                            });
                            if (!find[0]) {
                                if (6 > room.getSeats().size() && 0 == room.getGameStatus().compareTo(GameStatus.WAITING)) {
                                    room.addSeat(userResponse.getData(), 0);
                                } else {
                                    intoResponseBuilder.setError(GameBase.ErrorCode.COUNT_FULL);
                                    response.setOperationType(GameBase.OperationType.CONNECTION).setData(intoResponseBuilder.build().toByteString());
                                    messageReceive.send(response.build(), userId);
                                    redisService.unlock("lock_room" + messageReceive.roomNo);
                                    break;
                                }
                            }
                            room.sendRoomInfo(userId, intoResponseBuilder, response);
                            room.sendSeatInfo(response);

                            //是否竞技场
                            if (redisService.exists("room_match" + messageReceive.roomNo)) {
                                String matchNo = redisService.getCache("room_match" + messageReceive.roomNo);
                                if (redisService.exists("match_info" + matchNo)) {
                                    while (!redisService.lock("lock_match_info" + matchNo)) {
                                    }
                                    MatchInfo matchInfo = JSON.parseObject(redisService.getCache("match_info" + matchNo), MatchInfo.class);
                                    Arena arena = matchInfo.getArena();
                                    GameBase.MatchInfo matchInfoResponse = GameBase.MatchInfo.newBuilder().setArenaType(arena.getArenaType())
                                            .setCount(arena.getCount()).setEntryFee(arena.getEntryFee()).setName(arena.getName())
                                            .setReward(arena.getReward()).build();
                                    messageReceive.send(response.setOperationType(GameBase.OperationType.MATCH_INFO)
                                            .setData(matchInfoResponse.toByteString()).build(), userId);

                                    int status = matchInfo.getStatus();
                                    int round = 1;
                                    if (status == 3) {
                                        round = 2;
                                    }
                                    if (status == 4) {
                                        round = 3;
                                    }
                                    if (status > 2) {
                                        status = status == 5 ? 3 : 2;
                                    }
                                    GameBase.MatchData matchData = GameBase.MatchData.newBuilder()
                                            .setCurrentCount(matchInfo.getMatchUsers().size())
                                            .setStatus(status).setRound(round).build();
                                    messageReceive.send(response.setOperationType(GameBase.OperationType.MATCH_DATA)
                                            .setData(matchData.toByteString()).build(), userId);

                                    if (!matchInfo.isStart()) {
                                        List<Integer> roomNos = matchInfo.getRooms();
                                        for (Integer roomNo : roomNos) {
                                            new ReadyTimeout(roomNo, redisService, 0).start();
                                        }
                                        matchInfo.setStart(true);
                                        new MatchScoreTimeout(Integer.valueOf(matchNo), redisService).start();
                                    }
                                    redisService.addCache("match_info" + matchNo, JSON.toJSONString(matchInfo));
                                    redisService.unlock("lock_match_info" + matchNo);
                                }
                            }

                            if (0 != room.getGameStatus().compareTo(GameStatus.WAITING)) {
                                SanGong.SangongGameInfo.Builder gameInfo = SanGong.SangongGameInfo.newBuilder();
                                gameInfo.setGameCount(room.getGameCount());
                                gameInfo.setGameTimes(room.getGameTimes());
                                gameInfo.setBanker(room.getGrab());
                                addSeat(room, gameInfo);
                                response.setOperationType(GameBase.OperationType.GAME_INFO).setData(gameInfo.build().toByteString());
                                messageReceive.send(response.build(), userId);
                            }
                            SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
                            if (0 != room.getGameStatus().compareTo(GameStatus.WAITING) && 0 != room.getGameStatus().compareTo(GameStatus.READYING)
                                    && redisService.exists("room_match" + messageReceive.roomNo)) {
                                int time = 8 - (int) ((new Date().getTime() - room.getStatusDate().getTime()) / 1000);
                                statusResponse.setTimeCounter(time > 0 ? time : 0);
                            }
                            statusResponse.setGameStatus(SanGong.SangongGameStatus.forNumber(room.getGameStatus().ordinal())).build();
                            response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
                            messageReceive.send(response.build(), userId);

                            redisService.addCache("room" + messageReceive.roomNo, JSON.toJSONString(room));
                            redisService.unlock("lock_room" + messageReceive.roomNo);
                            if (redisService.exists("dissolve" + messageReceive.roomNo)) {

                                String dissolveStatus = redisService.getCache("dissolve" + messageReceive.roomNo);
                                String[] users = dissolveStatus.split("-");
                                String user = "0";
                                for (String s : users) {
                                    if (s.startsWith("1")) {
                                        user = s.substring(1);
                                        break;
                                    }
                                }

                                GameBase.DissolveApply dissolveApply = GameBase.DissolveApply.newBuilder()
                                        .setError(GameBase.ErrorCode.SUCCESS).setUserId(Integer.valueOf(user)).build();
                                response.setOperationType(GameBase.OperationType.DISSOLVE).setData(dissolveApply.toByteString());
                                if (SanGongTcpService.userClients.containsKey(userId)) {
                                    messageReceive.send(response.build(), userId);
                                }

                                GameBase.DissolveReplyResponse.Builder replyResponse = GameBase.DissolveReplyResponse.newBuilder();
                                for (Seat seat : room.getSeats()) {
                                    if (dissolveStatus.contains("-1" + seat.getUserId())) {
                                        replyResponse.addDissolve(GameBase.Dissolve.newBuilder().setUserId(seat.getUserId()).setAgree(true));
                                    } else if (dissolveStatus.contains("-2" + seat.getUserId())) {
                                        replyResponse.addDissolve(GameBase.Dissolve.newBuilder().setUserId(seat.getUserId()).setAgree(false));
                                    }
                                }
                                response.setOperationType(GameBase.OperationType.DISSOLVE_REPLY).setData(replyResponse.build().toByteString());
                                messageReceive.send(response.build(), userId);
                            }
                        } else if (redisService.exists("match_info" + messageReceive.roomNo)) {
                            while (!redisService.lock("lock_match_info" + messageReceive.roomNo)) {
                            }
                            MatchInfo matchInfo = JSON.parseObject(redisService.getCache("match_info" + messageReceive.roomNo), MatchInfo.class);
                            int score = 0;
                            for (MatchUser m : matchInfo.getMatchUsers()) {
                                if (m.getUserId() == userId) {
                                    score = m.getScore();
                                    break;
                                }
                            }
                            messageReceive.send(response.setOperationType(GameBase.OperationType.ROOM_INFO).clearData().build(), userId);
                            GameBase.RoomSeatsInfo.Builder roomSeatsInfo = GameBase.RoomSeatsInfo.newBuilder();
                            GameBase.SeatResponse.Builder seatResponse = GameBase.SeatResponse.newBuilder();
                            seatResponse.setSeatNo(1);
                            seatResponse.setID(userId);
                            seatResponse.setScore(score);
                            seatResponse.setReady(false);
                            seatResponse.setIp(userResponse.getData().getLastLoginIp());
                            seatResponse.setGameCount(userResponse.getData().getGameCount());
                            seatResponse.setNickname(userResponse.getData().getNickname());
                            seatResponse.setHead(userResponse.getData().getHead());
                            seatResponse.setSex(userResponse.getData().getSex().equals("1"));
                            seatResponse.setOffline(false);
                            seatResponse.setIsRobot(false);
                            roomSeatsInfo.addSeats(seatResponse.build());
                            messageReceive.send(response.setOperationType(GameBase.OperationType.SEAT_INFO).setData(roomSeatsInfo.build().toByteString()).build(), userId);
                            redisService.unlock("lock_match_info" + messageReceive.roomNo);

                        } else {
                            intoResponseBuilder.setError(GameBase.ErrorCode.ROOM_NOT_EXIST);
                            response.setOperationType(GameBase.OperationType.CONNECTION).setData(intoResponseBuilder.build().toByteString());
                            messageReceive.send(response.build(), userId);
                        }
                    }
                    break;
                case READY:
                    if (redisService.exists("room" + messageReceive.roomNo)) {
                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);

                        if (0 == room.getGameStatus().compareTo(GameStatus.READYING) || 0 == room.getGameStatus().compareTo(GameStatus.WAITING)) {
                            room.getSeats().stream().filter(seat -> seat.getUserId() == userId && !seat.isReady()).forEach(seat -> {
                                seat.setReady(true);
                                response.setOperationType(GameBase.OperationType.READY).setData(GameBase.ReadyResponse.newBuilder().setID(seat.getUserId()).build().toByteString());
                                room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 ->
                                        SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId()));
                            });
                            boolean allReady = true;
                            for (Seat seat : room.getSeats()) {
                                if (!seat.isReady()) {
                                    allReady = false;
                                    break;
                                }
                            }
                            if (allReady && room.getSeats().size() == room.getCount()) {
                                room.start(response, redisService);
                                if (1 == room.getGameCount()) {
                                    response.setOperationType(GameBase.OperationType.START).clearData();
                                    room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                        SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                                    });
                                }
                            }
                            redisService.addCache("room" + messageReceive.roomNo, JSON.toJSONString(room));
                        }
                        redisService.unlock("lock_room" + messageReceive.roomNo);
                    } else {
                        logger.warn("房间不存在");
                    }
                    break;
//                case START:
//                    if (redisService.exists("room" + messageReceive.roomNo)) {
//                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
//                        }
//                        Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
//                        if (0 == room.getGameStatus().compareTo(GameStatus.READYING) || 0 == room.getGameStatus().compareTo(GameStatus.WAITING)) {
//                            room.getSeats().stream().filter(seat -> seat.getUserId() == userId && !seat.isReady()).forEach(seat -> {
//                                seat.setReady(true);
//                                response.setOperationType(GameBase.OperationType.READY).setData(GameBase.ReadyResponse.newBuilder().setID(seat.getUserId()).build().toByteString());
//                                room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 ->
//                                        SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId()));
//                            });
//                            boolean allReady = true;
//                            for (Seat seat : room.getSeats()) {
//                                if (!seat.isReady()) {
//                                    allReady = false;
//                                    break;
//                                }
//                            }
//                            if (allReady && 1 < room.getSeats().size()) {
//                                if (0 == room.getGameStatus().compareTo(GameStatus.WAITING)) {
//                                    SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
//                                    statusResponse.setTimeCounter(redisService.exists("room_match" + messageReceive.roomNo) ? 8 : 0);
//                                    statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_READYING).build();
//                                    response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
//                                    room.getSeats().stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
//                                        messageReceive.send(response.build(), seat.getUserId());
//                                    });
//                                }
//                                for (Seat seat : room.getSeats()) {
//                                    seat.setReady(false);
//                                }
////                                if (1 == room.getBankerWay()) {
////                                    room.setGameCount(room.getGameCount() + 1);
////                                    room.setGameStatus(GameStatus.GRABING);
////                                    SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
////                                    statusResponse.setTimeCounter(redisService.exists("room_match" + messageReceive.roomNo) ? 8 : 0);
////                                    room.setStatusDate(new Date());
////                                    statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_GRABING).build();
////                                    response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
////                                    room.getSeats().stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
////                                        messageReceive.send(response.build(), seat.getUserId());
////                                    });
////                                } else {
////                                    room.compareGrab(0);
//                                room.setGameCount(room.getGameCount() + 1);
//                                room.setGameStatus(GameStatus.PLAYING);
//
////                                    SanGong.ConfirmBankerResponse confirmBankerResponse = SanGong.ConfirmBankerResponse.newBuilder()
////                                            .setBanker(room.getGrab()).build();
////                                    response.setOperationType(GameBase.OperationType.CONFIRM_BANKER).setData(confirmBankerResponse.toByteString());
////                                    room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
////                                        SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
////                                    });
//
//                                SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
//                                statusResponse.setTimeCounter(redisService.exists("room_match" + messageReceive.roomNo) ? 8 : 0);
//                                room.setStatusDate(new Date());
//                                statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_PLAYING).build();
//                                response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
//                                room.getSeats().stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
//                                    messageReceive.send(response.build(), seat.getUserId());
//                                });
////                                }
//                            } else {
//                                messageReceive.send(response.setOperationType(GameBase.OperationType.ERROR).setData(GameBase.ErrorResponse.newBuilder()
//                                        .setErrorCode(GameBase.ErrorCode.SHOUND_NOT_OPERATION).build().toByteString()).build(), userId);
//                            }
//                            redisService.addCache("room" + messageReceive.roomNo, JSON.toJSONString(room));
//                        }
//                        redisService.unlock("lock_room" + messageReceive.roomNo);
//                    } else {
//                        logger.warn("房间不存在");
//                    }
//                    break;
                case COMPLETED:
                    if (redisService.exists("room" + messageReceive.roomNo)) {
                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
                        room.getSeats().stream().filter(seat -> seat.getUserId() == userId && !seat.isCompleted())
                                .forEach(seat -> seat.setCompleted(true));
                        boolean allCompleted = true;
                        for (Seat seat : room.getSeats()) {
                            if (!seat.isCompleted()) {
                                allCompleted = false;
                                break;
                            }
                        }
                        if (allCompleted) {
                            //TODO 出牌超时
                        }
                        redisService.addCache("room" + messageReceive.roomNo, JSON.toJSONString(room));
                        redisService.unlock("lock_room" + messageReceive.roomNo);
                    } else {
                        logger.warn("房间不存在");
                    }
                    break;
                case ACTION:
                    GameBase.BaseAction actionRequest = GameBase.BaseAction.parseFrom(request.getData());
                    logger.info("sangong 接收 " + actionRequest.getOperationId() + userId);
                    GameBase.BaseAction.Builder actionResponse = GameBase.BaseAction.newBuilder();
                    actionResponse.setID(userId);
                    if (redisService.exists("room" + messageReceive.roomNo)) {
                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
                        switch (actionRequest.getOperationId()) {
//                            case GRAB:
//                                if (0 == room.getGameStatus().compareTo(GameStatus.GRABING)) {
//                                    if (room.getGrab() == 0) {
//                                        SanGong.GrabRequest grabRequest = SanGong.GrabRequest.parseFrom(actionRequest.getData());
//                                        room.getSeats().stream().filter(seat -> seat.getUserId() == userId && 0 == seat.getGrab()).forEach(seat -> {
//                                            seat.setGrab(grabRequest.getGrab() ? 1 : 2);
//
//                                            actionResponse.setOperationId(GameBase.ActionId.GRAB).setData(SanGong.GrabResponse.newBuilder()
//                                                    .setID(seat.getUserId()).setGrab(grabRequest.getGrab()).build().toByteString());
//                                            response.setOperationType(GameBase.OperationType.ACTION).setData(actionResponse.build().toByteString());
//                                            room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 ->
//                                                    SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId()));
//
////
//                                            if (grabRequest.getGrab()) {
//                                                room.compareGrab(userId);
//                                            } else {
//                                                //检查是否所有人都抢庄，抢庄了就发牌
//                                                boolean allGrab = true;
//                                                for (Seat seat1 : room.getSeats()) {
//                                                    if (0 == seat1.getGrab()) {
//                                                        allGrab = false;
//                                                        break;
//                                                    }
//                                                }
//                                                if (allGrab) {
//                                                    room.compareGrab(0);
//                                                } else {
//                                                    return;
//                                                }
//                                            }
//                                            room.setGameStatus(GameStatus.PLAYING);
//
//                                            SanGong.ConfirmBankerResponse confirmBankerResponse = SanGong.ConfirmBankerResponse.newBuilder()
//                                                    .setBanker(room.getGrab()).build();
//                                            response.setOperationType(GameBase.OperationType.CONFIRM_BANKER).setData(confirmBankerResponse.toByteString());
//                                            room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
//                                                SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
//                                            });
//
//                                            SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
//                                            statusResponse.setTimeCounter(redisService.exists("room_match" + messageReceive.roomNo) ? 8 : 0);
//                                            room.setStatusDate(new Date());
//                                            statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_PLAYING).build();
//                                            response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
//                                            room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
//                                                messageReceive.send(response.build(), seat1.getUserId());
//                                            });
////                                        }
//                                        });
//                                    }
//                                }
//                                break;
                            case PLAY_SCORE:
                                if (0 == room.getGameStatus().compareTo(GameStatus.PLAYING)) {
                                    SanGong.PlayScoreRequest playScoreRequest = SanGong.PlayScoreRequest.parseFrom(actionRequest.getData());
                                    room.getSeats().stream().filter(seat -> seat.getUserId() == userId && 0 == seat.getPlayScore()).forEach(seat -> {
                                        seat.setPlayScore(playScoreRequest.getScore());
                                        SanGong.PlayScoreResponse playScoreResponse = SanGong.PlayScoreResponse.newBuilder()
                                                .setID(seat.getUserId()).setScore(playScoreRequest.getScore()).build();
                                        actionResponse.setOperationId(GameBase.ActionId.PLAY_SCORE).setData(playScoreResponse.toByteString());
                                        response.setOperationType(GameBase.OperationType.ACTION).setData(actionResponse.build().toByteString());
                                        room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                            SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                                        });
                                        //检查是否所有人都下注，下注了就发牌
                                        boolean allPlay = true;
                                        for (Seat seat1 : room.getSeats()) {
                                            if (0 == seat1.getPlayScore() && seat1.getUserId() != room.getGrab()) {
                                                allPlay = false;
                                                break;
                                            }
                                        }
                                        if (allPlay) {
                                            room.allPlay(response, redisService);
                                        }

                                    });
                                }
                                break;

                            case OPEN_CARD:
                                if (0 == room.getGameStatus().compareTo(GameStatus.OPENING)) {
                                    room.getSeats().stream().filter(seat -> seat.getUserId() == userId && !seat.isOpen()).forEach(seat -> {
                                        seat.setOpen(true);
                                        SanGong.OpenCardResponse openCardResponse = SanGong.OpenCardResponse.newBuilder()
                                                .setID(seat.getUserId()).addAllCards(seat.getCards()).build();

                                        actionResponse.setOperationId(GameBase.ActionId.OPEN_CARD).setData(openCardResponse.toByteString());
                                        response.setOperationType(GameBase.OperationType.ACTION).setData(actionResponse.build().toByteString());
                                        room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                            SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                                        });
                                        //检查是否所有人都开牌，开牌了就结算
                                        boolean allOpen = true;
                                        for (Seat seat1 : room.getSeats()) {
                                            if (!seat1.isOpen()) {
                                                allOpen = false;
                                                break;
                                            }
                                        }
                                        if (allOpen) {
                                            room.gameOver(response, redisService);
                                        }

                                    });
                                }
                                break;
                        }
                        if (null != room.getRoomNo()) {
                            redisService.addCache("room" + messageReceive.roomNo, JSON.toJSONString(room));
                        }
                        redisService.unlock("lock_room" + messageReceive.roomNo);
                    } else {
                        logger.warn("房间不存在");
                    }
                    break;
                case REPLAY:
                    SanGong.SangongReplayResponse.Builder replayResponse = SanGong.SangongReplayResponse.newBuilder();
                    if (redisService.exists("room" + messageReceive.roomNo)) {
                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
                        for (OperationHistory operationHistory : room.getHistoryList()) {
                            GameBase.OperationHistory.Builder builder = GameBase.OperationHistory.newBuilder();
                            builder.setID(operationHistory.getUserId());
                            builder.addAllCard(operationHistory.getCards());
                            builder.setOperationId(GameBase.ActionId.PLAY_CARD);
                            replayResponse.addHistory(builder);
                        }
                        response.setOperationType(GameBase.OperationType.REPLAY).setData(replayResponse.build().toByteString());
                        messageReceive.send(response.build(), userId);
                        redisService.unlock("lock_room" + messageReceive.roomNo);
                    }
                    break;
                case EXIT:
                    if (redisService.exists("room" + messageReceive.roomNo) && !redisService.exists("room_match" + messageReceive.roomNo)) {
                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                        }
                        GameBase.ExitRoom.Builder exitRoom = GameBase.ExitRoom.newBuilder();
                        Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
                        if (0 == room.getGameStatus().compareTo(GameStatus.WAITING)) {
                            for (Seat seat : room.getSeats()) {
                                if (seat.getUserId() == userId) {
                                    exitRoom.setUserId(userId);
                                    String uuid = UUID.randomUUID().toString().replace("-", "");
                                    while (redisService.exists(uuid)) {
                                        uuid = UUID.randomUUID().toString().replace("-", "");
                                    }
                                    redisService.addCache("backkey" + uuid, seat.getUserId() + "", 1800);
                                    exitRoom.setBackKey(uuid);
                                    response.setOperationType(GameBase.OperationType.EXIT).setData(exitRoom.build().toByteString());
                                    messageReceive.send(response.build(), userId);
                                    room.getSeatNos().add(seat.getSeatNo());
                                    room.getSeats().remove(seat);
                                    redisService.delete("reconnect" + seat.getUserId());
                                    room.sendSeatInfo(response);
                                    redisService.addCache("room" + messageReceive.roomNo, JSON.toJSONString(room));
                                    break;
                                }
                            }
                        } else {
                            exitRoom.setError(GameBase.ErrorCode.SHOUND_NOT_OPERATION);
                            response.setOperationType(GameBase.OperationType.EXIT).setData(exitRoom.build().toByteString());
                            messageReceive.send(response.build(), userId);
                        }
                        redisService.unlock("lock_room" + messageReceive.roomNo);
                    }
                    break;
                case DISSOLVE:
                    if (redisService.exists("room" + messageReceive.roomNo) && !redisService.exists("room_match" + messageReceive.roomNo)) {
                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                        }
                        if (!redisService.exists("dissolve" + messageReceive.roomNo) && !redisService.exists("delete_dissolve" + messageReceive.roomNo)) {
                            GameBase.DissolveApply dissolveApply = GameBase.DissolveApply.newBuilder()
                                    .setError(GameBase.ErrorCode.SUCCESS).setUserId(userId).build();
                            Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
                            redisService.addCache("dissolve" + messageReceive.roomNo, "-1" + userId);
                            response.setOperationType(GameBase.OperationType.DISSOLVE).setData(dissolveApply.toByteString());
                            for (Seat seat : room.getSeats()) {
                                if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                    messageReceive.send(response.build(), seat.getUserId());
                                }
                            }

                            GameBase.DissolveReplyResponse.Builder replyResponse = GameBase.DissolveReplyResponse.newBuilder();
                            replyResponse.addDissolve(GameBase.Dissolve.newBuilder().setUserId(userId).setAgree(true));
                            response.setOperationType(GameBase.OperationType.DISSOLVE_REPLY).setData(dissolveApply.toByteString());
                            for (Seat seat : room.getSeats()) {
                                if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                    messageReceive.send(response.build(), seat.getUserId());
                                }
                            }

                            if (1 == room.getSeats().size()) {
                                GameBase.DissolveConfirm dissolveConfirm = GameBase.DissolveConfirm.newBuilder().setDissolved(true).build();
                                response.setOperationType(GameBase.OperationType.DISSOLVE_CONFIRM).setData(dissolveConfirm.toByteString());
                                for (Seat seat : room.getSeats()) {
                                    if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                        messageReceive.send(response.build(), seat.getUserId());
                                    }
                                }
                                room.roomOver(response, redisService);
                            } else {
                                new DissolveTimeout(Integer.valueOf(messageReceive.roomNo), redisService).start();
                            }
                        } else {
                            response.setOperationType(GameBase.OperationType.DISSOLVE).setData(GameBase.DissolveApply.newBuilder()
                                    .setError(GameBase.ErrorCode.AREADY_DISSOLVE).build().toByteString());
                            messageReceive.send(response.build(), userId);
                        }
                        redisService.unlock("lock_room" + messageReceive.roomNo);
                    }
                    break;
                case DISSOLVE_REPLY:
                    GameBase.DissolveReplyRequest dissolveReply = GameBase.DissolveReplyRequest.parseFrom(request.getData());
                    if (redisService.exists("room" + messageReceive.roomNo)) {
                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                        }
                        while (!redisService.lock("lock_dissolve" + messageReceive.roomNo)) {
                        }
                        if (redisService.exists("dissolve" + messageReceive.roomNo)) {
                            Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
                            String dissolveStatus = redisService.getCache("dissolve" + messageReceive.roomNo);
                            if (dissolveReply.getAgree()) {
                                dissolveStatus = dissolveStatus + "-1" + userId;
                            } else {
                                dissolveStatus = dissolveStatus + "-2" + userId;
                            }
                            redisService.addCache("dissolve" + messageReceive.roomNo, dissolveStatus);
                            int disagree = 0;
                            int agree = 0;
                            GameBase.DissolveReplyResponse.Builder replyResponse = GameBase.DissolveReplyResponse.newBuilder();
                            for (Seat seat : room.getSeats()) {
                                if (dissolveStatus.contains("-1" + seat.getUserId())) {
                                    replyResponse.addDissolve(GameBase.Dissolve.newBuilder().setUserId(userId).setAgree(true));
                                    agree++;
                                } else if (dissolveStatus.contains("-2" + seat.getUserId())) {
                                    replyResponse.addDissolve(GameBase.Dissolve.newBuilder().setUserId(userId).setAgree(false));
                                    disagree++;
                                }
                            }
                            response.setOperationType(GameBase.OperationType.DISSOLVE_REPLY).setData(replyResponse.build().toByteString());
                            for (Seat seat : room.getSeats()) {
                                if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                    messageReceive.send(response.build(), seat.getUserId());
                                }
                            }

                            if (disagree >= room.getSeats().size() / 2) {
                                GameBase.DissolveConfirm dissolveConfirm = GameBase.DissolveConfirm.newBuilder().setDissolved(false).build();
                                response.setOperationType(GameBase.OperationType.DISSOLVE_CONFIRM).setData(dissolveConfirm.toByteString());
                                for (Seat seat : room.getSeats()) {
                                    if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                        messageReceive.send(response.build(), seat.getUserId());
                                    }
                                }
                                redisService.delete("dissolve" + messageReceive.roomNo);
//                                redisService.addCache("delete_dissolve" + messageReceive.roomNo, "", 60);
                            } else if (agree > room.getSeats().size() / 2) {
                                GameBase.DissolveConfirm dissolveConfirm = GameBase.DissolveConfirm.newBuilder().setDissolved(true).build();
                                response.setOperationType(GameBase.OperationType.DISSOLVE_CONFIRM).setData(dissolveConfirm.toByteString());
                                for (Seat seat : room.getSeats()) {
                                    if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                        messageReceive.send(response.build(), seat.getUserId());
                                    }
                                }
                                room.roomOver(response, redisService);
                                redisService.delete("dissolve" + messageReceive.roomNo);
                            }
                        }
                        redisService.unlock("lock_dissolve" + messageReceive.roomNo);
                        redisService.unlock("lock_room" + messageReceive.roomNo);
                    }
                    break;
                case MESSAGE:
                    if (redisService.exists("room" + messageReceive.roomNo)) {
                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
                        GameBase.Message message = GameBase.Message.parseFrom(request.getData());

                        GameBase.Message messageResponse = GameBase.Message.newBuilder().setUserId(userId)
                                .setMessageType(message.getMessageType()).setContent(message.getContent()).build();

                        for (Seat seat : room.getSeats()) {
                            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                messageReceive.send(response.setOperationType(GameBase.OperationType.MESSAGE)
                                        .setData(messageResponse.toByteString()).build(), seat.getUserId());
                            }
                        }
                        redisService.unlock("lock_room" + messageReceive.roomNo);
                    }
                    break;
                case INTERACTION:
                    if (redisService.exists("room" + messageReceive.roomNo)) {
                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
                        GameBase.AppointInteraction appointInteraction = GameBase.AppointInteraction.parseFrom(request.getData());

                        GameBase.AppointInteraction appointInteractionResponse = GameBase.AppointInteraction.newBuilder().setUserId(userId)
                                .setToUserId(appointInteraction.getToUserId()).setContentIndex(appointInteraction.getContentIndex()).build();
                        for (Seat seat : room.getSeats()) {
                            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                messageReceive.send(response.setOperationType(GameBase.OperationType.INTERACTION)
                                        .setData(appointInteractionResponse.toByteString()).build(), seat.getUserId());
                            }
                        }
                        redisService.unlock("lock_room" + messageReceive.roomNo);
                    }
                    break;
                case ONLINE:
                    if (redisService.exists("room" + messageReceive.roomNo)) {
                        while (!redisService.lock("lock_room" + messageReceive.roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + messageReceive.roomNo), Room.class);
                        GameBase.Online online = GameBase.Online.parseFrom(request.getData());
                        for (Seat seat : room.getSeats()) {
                            if (seat.getUserId() == userId) {
                                if (online.getOnline() && seat.isRobot()) {
                                    seat.setRobot(false);
                                } else if (!online.getOnline() && !seat.isRobot()) {
                                    seat.setRobot(true);
                                } else {
                                    break;
                                }
                                response.setOperationType(GameBase.OperationType.ONLINE).setData(online.toBuilder().setUserId(userId).build().toByteString());
                                for (Seat seat1 : room.getSeats()) {
                                    if (SanGongTcpService.userClients.containsKey(seat1.getUserId())) {
                                        messageReceive.send(response.build(), seat1.getUserId());
                                    }
                                }
                                redisService.addCache("room" + messageReceive.roomNo, JSON.toJSONString(room));
                                break;
                            }
                        }
                        redisService.unlock("lock_room" + messageReceive.roomNo);
                    }
                    break;
                case LOGGER:
                    GameBase.LoggerRequest loggerRequest = GameBase.LoggerRequest.parseFrom(request.getData());
                    LoggerUtil.logger(userId + "----" + loggerRequest.getLogger());
                    break;
            }
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
    }
}