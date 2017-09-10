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
import sangong.utils.HttpUtil;
import sangong.utils.LoggerUtil;

import java.util.Date;

/**
 * Created date 2016/3/25
 * Author pengyi
 */
public class SanGongClient {

    private Logger logger = LoggerFactory.getLogger(this.getClass());
    private int userId;
    private RedisService redisService;
    private String roomNo;

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
                }
            }
            if (redisService.exists("room" + roomNo)) {
                while (!redisService.lock("lock_room" + roomNo)) {
                }
                Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
                if (null == room) {
                    return;
                }
                for (Seat seat : room.getSeats()) {
                    if (seat.getUserId() == userId) {
                        seat.setRobot(true);
                        break;
                    }
                }
                room.sendSeatInfo(response);

                redisService.addCache("room" + roomNo, JSON.toJSONString(room));
                redisService.unlock("lock_room" + roomNo);
            }
        }
    }

    private void addSeat(Room room, SanGong.SangongGameInfo.Builder gameInfo) {
        for (Seat seat1 : room.getSeats()) {
            SanGong.SangongSeatGameInfo.Builder seatResponse = SanGong.SangongSeatGameInfo.newBuilder();
            seatResponse.setID(seat1.getUserId());
            seatResponse.setScore(seat1.getScore());
            seatResponse.setIsRobot(seat1.isRobot());
            seatResponse.setGrab(seat1.getGrab());
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
            switch (request.getOperationType()) {
                case CONNECTION:
                    //加入玩家数据
                    if (redisService.exists("maintenance")) {
                        break;
                    }
                    GameBase.RoomCardIntoRequest intoRequest = GameBase.RoomCardIntoRequest.parseFrom(request.getData());
                    userId = intoRequest.getID();
                    roomNo = intoRequest.getRoomNo();
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
                    SanGongTcpService.userClients.put(userId, messageReceive);
                    GameBase.RoomCardIntoResponse.Builder intoResponseBuilder = GameBase.RoomCardIntoResponse.newBuilder();
                    intoResponseBuilder.setGameType(GameBase.GameType.SANGONG).setRoomNo(roomNo);
                    if (redisService.exists("room" + roomNo)) {
                        while (!redisService.lock("lock_room" + roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
                        intoResponseBuilder.setRoomOwner(room.getRoomOwner());
                        intoResponseBuilder.setStarted(0 != room.getGameStatus().compareTo(GameStatus.READYING) && 0 != room.getGameStatus().compareTo(GameStatus.WAITING));
                        if (0 == room.getGameStatus().compareTo(GameStatus.READYING) && redisService.exists("room_match" + roomNo)) {
                            int time = 8 - (int) ((new Date().getTime() - room.getStartDate().getTime()) / 1000);
                            intoResponseBuilder.setReadyTimeCounter(time > 0 ? time : 0);
                        }
                        redisService.addCache("reconnect" + userId, "sangong," + roomNo);
                        //房间是否已存在当前用户，存在则为重连
                        final boolean[] find = {false};
                        room.getSeats().stream().filter(seat -> seat.getUserId() == userId).forEach(seat -> {
                            find[0] = true;
                            seat.setRobot(false);
                        });
                        if (!find[0]) {
                            if (6 > room.getSeats().size() && 0 == room.getGameStatus().compareTo(GameStatus.WAITING)) {
                                JSONObject jsonObject = new JSONObject();
                                jsonObject.put("userId", userId);
                                ApiResponse<User> userResponse = JSON.parseObject(HttpUtil.urlConnectionByRsa(Constant.apiUrl + Constant.userInfoUrl, jsonObject.toJSONString()), new TypeReference<ApiResponse<User>>() {
                                });
                                if (0 == userResponse.getCode()) {
                                    room.addSeat(userResponse.getData());
                                }
                            } else {
                                intoResponseBuilder.setError(GameBase.ErrorCode.COUNT_FULL);
                                response.setOperationType(GameBase.OperationType.CONNECTION).setData(intoResponseBuilder.build().toByteString());
                                messageReceive.send(response.build(), userId);
                                redisService.unlock("lock_room" + roomNo);
                                break;
                            }
                        }
                        room.sendRoomInfo(userId, intoResponseBuilder, response);
                        room.sendSeatInfo(response);

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
                                && redisService.exists("room_match" + roomNo)) {
                            int time = 8 - (int) ((new Date().getTime() - room.getStatusDate().getTime()) / 1000);
                            statusResponse.setTimeCounter(time > 0 ? time : 0);
                        }
                        statusResponse.setGameStatus(SanGong.SangongGameStatus.forNumber(room.getGameStatus().ordinal())).build();
                        response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
                        messageReceive.send(response.build(), userId);

                        redisService.addCache("room" + roomNo, JSON.toJSONString(room));
                        redisService.unlock("lock_room" + roomNo);
                    } else {
                        intoResponseBuilder.setError(GameBase.ErrorCode.ROOM_NOT_EXIST);
                        response.setOperationType(GameBase.OperationType.CONNECTION).setData(intoResponseBuilder.build().toByteString());
                        messageReceive.send(response.build(), userId);
                    }
                    break;
                case READY:
                    if (redisService.exists("room" + roomNo)) {
                        while (!redisService.lock("lock_room" + roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);

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
                            if (allReady && (0 != room.getGameStatus().compareTo(GameStatus.WAITING) || room.getSeats().size() == 6)) {

                                SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
                                statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
                                statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_READYING).build();
                                response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
                                room.getSeats().stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
                                    messageReceive.send(response.build(), seat.getUserId());
                                });

                                for (Seat seat : room.getSeats()) {
                                    seat.setReady(false);
                                }
                                if (1 == room.getBankerWay()) {
                                    room.setGameCount(room.getGameCount() + 1);
                                    room.setGameStatus(GameStatus.GRABING);
                                    statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
                                    statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
                                    room.setStatusDate(new Date());
                                    statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_GRABING).build();
                                    response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
                                    room.getSeats().stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
                                        messageReceive.send(response.build(), seat.getUserId());
                                    });
                                } else {
                                    room.compareGrab(0);
                                    room.setGameStatus(GameStatus.PLAYING);
                                    statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
                                    statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
                                    room.setStatusDate(new Date());
                                    statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_PLAYING).build();
                                    response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
                                    room.getSeats().stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
                                        messageReceive.send(response.build(), seat.getUserId());
                                    });

                                    SanGong.ConfirmBankerResponse confirmBankerResponse = SanGong.ConfirmBankerResponse.newBuilder()
                                            .setBanker(room.getGrab()).build();
                                    response.setOperationType(GameBase.OperationType.CONFIRM_BANKER).setData(confirmBankerResponse.toByteString());
                                    room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                        SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                                    });
                                }
                            }
                            redisService.addCache("room" + roomNo, JSON.toJSONString(room));
                        }
                        redisService.unlock("lock_room" + roomNo);
                    } else {
                        logger.warn("房间不存在");
                    }
                    break;
                case START:
                    if (redisService.exists("room" + roomNo)) {
                        while (!redisService.lock("lock_room" + roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
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
                            if (allReady && 1 < room.getSeats().size()) {
                                if (0 == room.getGameStatus().compareTo(GameStatus.WAITING)) {
                                    SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
                                    statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
                                    statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_READYING).build();
                                    response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
                                    room.getSeats().stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
                                        messageReceive.send(response.build(), seat.getUserId());
                                    });
                                }
                                for (Seat seat : room.getSeats()) {
                                    seat.setReady(false);
                                }
                                if (1 == room.getBankerWay()) {
                                    room.setGameCount(room.getGameCount() + 1);
                                    room.setGameStatus(GameStatus.GRABING);
                                    SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
                                    statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
                                    room.setStatusDate(new Date());
                                    statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_GRABING).build();
                                    response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
                                    room.getSeats().stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
                                        messageReceive.send(response.build(), seat.getUserId());
                                    });
                                } else {
                                    room.compareGrab(0);
                                    room.setGameStatus(GameStatus.PLAYING);
                                    SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
                                    statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
                                    room.setStatusDate(new Date());
                                    statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_PLAYING).build();
                                    response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
                                    room.getSeats().stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
                                        messageReceive.send(response.build(), seat.getUserId());
                                    });

                                    SanGong.ConfirmBankerResponse confirmBankerResponse = SanGong.ConfirmBankerResponse.newBuilder()
                                            .setBanker(room.getGrab()).build();
                                    response.setOperationType(GameBase.OperationType.CONFIRM_BANKER).setData(confirmBankerResponse.toByteString());
                                    room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                        SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                                    });
                                }
                            } else {
                                messageReceive.send(response.setOperationType(GameBase.OperationType.ERROR).setData(GameBase.ErrorResponse.newBuilder()
                                        .setErrorCode(GameBase.ErrorCode.SHOUND_NOT_OPERATION).build().toByteString()).build(), userId);
                            }
                            redisService.addCache("room" + roomNo, JSON.toJSONString(room));
                        }
                        redisService.unlock("lock_room" + roomNo);
                    } else {
                        logger.warn("房间不存在");
                    }
                    break;
                case COMPLETED:
                    if (redisService.exists("room" + roomNo)) {
                        while (!redisService.lock("lock_room" + roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
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
                        redisService.addCache("room" + roomNo, JSON.toJSONString(room));
                        redisService.unlock("lock_room" + roomNo);
                    } else {
                        logger.warn("房间不存在");
                    }
                    break;
                case ACTION:
                    GameBase.BaseAction actionRequest = GameBase.BaseAction.parseFrom(request.getData());
                    GameBase.BaseAction.Builder actionResponse = GameBase.BaseAction.newBuilder();
                    actionResponse.setID(userId);
                    if (redisService.exists("room" + roomNo)) {
                        while (!redisService.lock("lock_room" + roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
                        switch (actionRequest.getOperationId()) {
                            case GRAB:
                                if (0 == room.getGameStatus().compareTo(GameStatus.GRABING)) {
                                    if (room.getGrab() == 0) {
                                        SanGong.GrabRequest grabRequest = SanGong.GrabRequest.parseFrom(actionRequest.getData());
                                        room.getSeats().stream().filter(seat -> seat.getUserId() == userId && 0 == seat.getGrab()).forEach(seat -> {
                                            seat.setGrab(grabRequest.getGrab() ? 1 : 2);

                                            actionResponse.setOperationId(GameBase.ActionId.GRAB).setData(SanGong.GrabResponse.newBuilder()
                                                    .setID(seat.getUserId()).setGrab(grabRequest.getGrab()).build().toByteString());
                                            response.setOperationType(GameBase.OperationType.ACTION).setData(actionResponse.build().toByteString());
                                            room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 ->
                                                    SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId()));

//
                                            if (grabRequest.getGrab()) {
                                                room.compareGrab(userId);
                                            } else {
                                                //检查是否所有人都抢庄，抢庄了就发牌
                                                boolean allGrab = true;
                                                for (Seat seat1 : room.getSeats()) {
                                                    if (0 == seat1.getGrab()) {
                                                        allGrab = false;
                                                        break;
                                                    }
                                                }
                                                if (allGrab) {
                                                    room.compareGrab(0);
                                                } else {
                                                    return;
                                                }
                                            }
                                            room.setGameStatus(GameStatus.PLAYING);

                                            SanGong.ConfirmBankerResponse confirmBankerResponse = SanGong.ConfirmBankerResponse.newBuilder()
                                                    .setBanker(room.getGrab()).build();
                                            response.setOperationType(GameBase.OperationType.CONFIRM_BANKER).setData(confirmBankerResponse.toByteString());
                                            room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                                SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                                            });

                                            SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
                                            statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
                                            room.setStatusDate(new Date());
                                            statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_PLAYING).build();
                                            response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
                                            room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                                messageReceive.send(response.build(), seat1.getUserId());
                                            });
//                                        }
                                        });
                                    }
                                }
                                break;
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
                                            room.dealCard();
                                            room.setGameStatus(GameStatus.OPENING);

                                            SanGong.DealCard.Builder dealCard = SanGong.DealCard.newBuilder();
                                            response.setOperationType(GameBase.OperationType.DEAL_CARD);
                                            room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                                dealCard.clearCards();
                                                dealCard.addAllCards(seat1.getCards());
                                                response.setData(dealCard.build().toByteString());
                                                SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                                            });

                                            SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
                                            statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
                                            room.setStatusDate(new Date());
                                            statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_OPENING).build();
                                            response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
                                            room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                                messageReceive.send(response.build(), seat1.getUserId());
                                            });
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
                            redisService.addCache("room" + roomNo, JSON.toJSONString(room));
                        }
                        redisService.unlock("lock_room" + roomNo);
                    } else {
                        logger.warn("房间不存在");
                    }
                    break;
                case REPLAY:
                    SanGong.SangongReplayResponse.Builder replayResponse = SanGong.SangongReplayResponse.newBuilder();
                    if (redisService.exists("room" + roomNo)) {
                        while (!redisService.lock("lock_room" + roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
                        for (OperationHistory operationHistory : room.getHistoryList()) {
                            GameBase.OperationHistory.Builder builder = GameBase.OperationHistory.newBuilder();
                            builder.setID(operationHistory.getUserId());
                            builder.addAllCard(operationHistory.getCards());
                            builder.setOperationId(GameBase.ActionId.PLAY_CARD);
                            replayResponse.addHistory(builder);
                        }
                        response.setOperationType(GameBase.OperationType.REPLAY).setData(replayResponse.build().toByteString());
                        messageReceive.send(response.build(), userId);
                        redisService.unlock("lock_room" + roomNo);
                    }
                    break;
                case EXIT:
                    break;
                case DISSOLVE:
                    if (redisService.exists("room" + roomNo)) {
                        while (!redisService.lock("lock_room" + roomNo)) {
                        }
                        redisService.addCache("dissolve" + roomNo, "-" + userId);
                        GameBase.DissolveApply dissolveApply = GameBase.DissolveApply.newBuilder().setUserId(userId).build();
                        Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
                        response.setOperationType(GameBase.OperationType.DISSOLVE).setData(dissolveApply.toByteString());
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
                        }
                        redisService.unlock("lock_room" + roomNo);
                    }
                    break;
                case DISSOLVE_REPLY:
                    GameBase.DissolveReply dissolveReply = GameBase.DissolveReply.parseFrom(request.getData());
                    if (redisService.exists("room" + roomNo)) {
                        while (!redisService.lock("lock_room" + roomNo)) {
                        }
                        while (!redisService.lock("lock_dissolve" + roomNo)) {
                        }
                        if (redisService.exists("dissolve" + roomNo)) {
                            Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
                            response.setOperationType(GameBase.OperationType.DISSOLVE_REPLY).setData(dissolveReply.toBuilder().setUserId(userId).build().toByteString());
                            boolean confirm = true;
                            String dissolveStatus = redisService.getCache("dissolve" + roomNo);
                            for (Seat seat : room.getSeats()) {
                                if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                    messageReceive.send(response.build(), seat.getUserId());
                                }
                                if (!dissolveStatus.contains("-" + seat.getUserId()) && seat.getUserId() != userId) {
                                    confirm = false;
                                }
                            }
                            if (!dissolveReply.getAgree()) {
                                GameBase.DissolveConfirm dissolveConfirm = GameBase.DissolveConfirm.newBuilder().setDissolved(false).build();
                                response.setOperationType(GameBase.OperationType.DISSOLVE_CONFIRM).setData(dissolveConfirm.toByteString());
                                for (Seat seat : room.getSeats()) {
                                    if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                        messageReceive.send(response.build(), seat.getUserId());
                                    }
                                }
                                redisService.delete("dissolve" + roomNo);
                            } else if (confirm) {
                                GameBase.DissolveConfirm dissolveConfirm = GameBase.DissolveConfirm.newBuilder().setDissolved(true).build();
                                response.setOperationType(GameBase.OperationType.DISSOLVE_CONFIRM).setData(dissolveConfirm.toByteString());
                                for (Seat seat : room.getSeats()) {
                                    if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                        messageReceive.send(response.build(), seat.getUserId());
                                    }
                                }
                                room.roomOver(response, redisService);
                                redisService.delete("dissolve" + roomNo);
                            } else {
                                redisService.addCache("dissolve" + roomNo, dissolveStatus + "-" + userId);
                            }
                        }
                        redisService.unlock("lock_dissolve" + roomNo);
                        redisService.unlock("lock_room" + roomNo);
                    }
                    break;
                case MESSAGE:
                    if (redisService.exists("room" + roomNo)) {
                        while (!redisService.lock("lock_room" + roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
                        GameBase.Message message = GameBase.Message.parseFrom(request.getData());

                        GameBase.Message messageResponse = GameBase.Message.newBuilder().setUserId(userId)
                                .setMessageType(message.getMessageType()).setContent(message.getContent()).build();

                        for (Seat seat : room.getSeats()) {
                            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                messageReceive.send(response.setOperationType(GameBase.OperationType.MESSAGE)
                                        .setData(messageResponse.toByteString()).build(), seat.getUserId());
                            }
                        }
                        redisService.unlock("lock_room" + roomNo);
                    }
                    break;
                case INTERACTION:
                    if (redisService.exists("room" + roomNo)) {
                        while (!redisService.lock("lock_room" + roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
                        GameBase.AppointInteraction appointInteraction = GameBase.AppointInteraction.parseFrom(request.getData());

                        GameBase.AppointInteraction appointInteractionResponse = GameBase.AppointInteraction.newBuilder().setUserId(userId)
                                .setToUserId(appointInteraction.getToUserId()).setContentIndex(appointInteraction.getContentIndex()).build();
                        for (Seat seat : room.getSeats()) {
                            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                messageReceive.send(response.setOperationType(GameBase.OperationType.MESSAGE)
                                        .setData(appointInteractionResponse.toByteString()).build(), seat.getUserId());
                            }
                        }
                        redisService.unlock("lock_room" + roomNo);
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