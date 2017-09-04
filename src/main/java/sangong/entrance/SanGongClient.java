package sangong.entrance;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sangong.mode.*;
import sangong.redis.RedisService;
import sangong.utils.HttpUtil;
import sangong.utils.LoggerUtil;

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
            if (redisService.exists("room" + roomNo)) {
                while (!redisService.lock("lock_room" + roomNo)) {
                }
                Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);

                for (Seat seat : room.getSeats()) {
                    if (seat.getUserId() == userId) {
                        seat.setRobot(true);
                        break;
                    }
                }
                SanGong.SangongGameInfo.Builder gameInfo = SanGong.SangongGameInfo.newBuilder();
                gameInfo.setGameStatus(SanGong.SangongGameStatus.forNumber(room.getGameStatus().ordinal()));
                gameInfo.setGameCount(room.getGameCount());
                gameInfo.setGameTimes(room.getGameTimes());
                addSeat(room, gameInfo);
                response.setOperationType(GameBase.OperationType.GAME_INFO).setData(gameInfo.build().toByteString());
                messageReceive.send(response.build(), userId);

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
            if (null != seat1.getCards()) {
                if (seat1.getUserId() == userId) {
                    seatResponse.addAllCards(seat1.getCards());
                } else {
                    seatResponse.setCardsSize(seat1.getCards().size());
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
                        redisService.addCache("reconnect" + userId, "sangong," + roomNo);
                        //房间是否已存在当前用户，存在则为重连
                        final boolean[] find = {false};
                        room.getSeats().stream().filter(seat -> seat.getUserId() == userId).forEach(seat -> find[0] = true);
                        if (!find[0]) {
                            if (6 > room.getSeats().size() && 0 == room.getGameStatus().compareTo(GameStatus.WAITING)) {
                                JSONObject jsonObject = new JSONObject();
                                jsonObject.put("userId", userId);
                                ApiResponse<User> userResponse = JSON.parseObject(HttpUtil.urlConnectionByRsa("http://127.0.0.1:9999/api/user/info", jsonObject.toJSONString()), new TypeReference<ApiResponse<User>>() {
                                });
                                if (0 == userResponse.getCode()) {
                                    room.addSeat(userResponse.getData());
                                }
                                room.addSeat(userResponse.getData());
                            } else {
                                intoResponseBuilder.setError(GameBase.ErrorCode.COUNT_FULL);
                                response.setOperationType(GameBase.OperationType.CONNECTION).setData(intoResponseBuilder.build().toByteString());
                                messageReceive.send(response.build(), userId);
                                redisService.unlock("lock_room" + roomNo);
                                break;
                            }
                        }
                        SanGong.SangongIntoResponse sangongIntoResponse = SanGong.SangongIntoResponse.newBuilder()
                                .setBaseScore(room.getBaseScore()).setBankerWay(room.getBankerWay()).setGameTimes(room.getGameTimes()).build();
                        intoResponseBuilder.setData(sangongIntoResponse.toByteString());

                        response.setOperationType(GameBase.OperationType.ROOM_INFO).setData(intoResponseBuilder.build().toByteString());
                        messageReceive.send(response.build(), userId);

                        GameBase.RoomSeatsInfo.Builder roomSeatsInfo = GameBase.RoomSeatsInfo.newBuilder();
                        for (Seat seat1 : room.getSeats()) {
                            GameBase.SeatResponse.Builder seatResponse = GameBase.SeatResponse.newBuilder();
                            seatResponse.setSeatNo(seat1.getSeatNo());
                            seatResponse.setID(seat1.getUserId());
                            seatResponse.setScore(seat1.getScore());
                            seatResponse.setReady(seat1.isReady());
                            seatResponse.setAreaString(seat1.getAreaString());
                            seatResponse.setNickname(seat1.getNickname());
                            seatResponse.setHead(seat1.getHead());
                            seatResponse.setSex(seat1.isSex());
                            roomSeatsInfo.addSeats(seatResponse.build());
                        }
                        response.setOperationType(GameBase.OperationType.SEAT_INFO).setData(roomSeatsInfo.build().toByteString());
                        for (Seat seat : room.getSeats()) {
                            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                messageReceive.send(response.build(), seat.getUserId());
                            }
                        }

                        if (0 != room.getGameStatus().compareTo(GameStatus.WAITING)) {
                            SanGong.SangongGameInfo.Builder gameInfo = SanGong.SangongGameInfo.newBuilder();
                            gameInfo.setGameStatus(SanGong.SangongGameStatus.forNumber(room.getGameStatus().ordinal()));
                            gameInfo.setGameCount(room.getGameCount());
                            gameInfo.setGameTimes(room.getGameTimes());
                            addSeat(room, gameInfo);
                            response.setOperationType(GameBase.OperationType.GAME_INFO).setData(gameInfo.build().toByteString());
                            messageReceive.send(response.build(), userId);
                        }
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
                            if (1 == room.getBankerWay()) {
                                room.setGameCount(room.getGameCount() + 1);
                                room.setGameStatus(GameStatus.GRABING);
                                response.setOperationType(GameBase.OperationType.START).clear();

                                room.getSeats().stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
                                    SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
                                });
                            } else {
                                room.compareGrab();
                                room.setGameStatus(GameStatus.PLAYING);

                                SanGong.ConfirmBankerResponse confirmBankerResponse = SanGong.ConfirmBankerResponse.newBuilder()
                                        .setBanker(room.getGrab()).build();
                                response.setOperationType(GameBase.OperationType.CONFIRM_BANKER).setData(confirmBankerResponse.toByteString());
                                room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                    SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                                });
                            }
                        }

                        redisService.addCache("room" + roomNo, JSON.toJSONString(room));
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
                        if (allReady) {
                            if (1 == room.getBankerWay()) {
                                room.setGameCount(room.getGameCount() + 1);
                                room.setGameStatus(GameStatus.GRABING);
                                response.setOperationType(GameBase.OperationType.START).clear();

                                room.getSeats().stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
                                    SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
                                });
                            } else {
                                room.compareGrab();
                                room.setGameStatus(GameStatus.PLAYING);

                                SanGong.ConfirmBankerResponse confirmBankerResponse = SanGong.ConfirmBankerResponse.newBuilder()
                                        .setBanker(room.getGrab()).build();
                                response.setOperationType(GameBase.OperationType.CONFIRM_BANKER).setData(confirmBankerResponse.toByteString());
                                room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                    SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                                });
                            }
                        }
                        redisService.addCache("room" + roomNo, JSON.toJSONString(room));
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
                    if (redisService.exists("room" + roomNo)) {
                        while (!redisService.lock("lock_room" + roomNo)) {
                        }
                        Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
                        switch (actionRequest.getOperationId()) {
                            case GRAB:
                                SanGong.GrabRequest grabRequest = SanGong.GrabRequest.parseFrom(actionRequest.getData());
                                room.getSeats().stream().filter(seat -> seat.getUserId() == userId && 0 == seat.getGrab()).forEach(seat -> {
                                    seat.setGrab(grabRequest.getGrab() ? 1 : 2);

                                    actionResponse.setOperationId(GameBase.ActionId.GRAB).setData(SanGong.GrabResponse.newBuilder()
                                            .setID(seat.getUserId()).setGrab(grabRequest.getGrab()).build().toByteString());
                                    response.setOperationType(GameBase.OperationType.ACTION).setData(actionResponse.build().toByteString());
                                    room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 ->
                                            SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId()));

                                    //检查是否所有人都抢庄，抢庄了就发牌
                                    boolean allGrab = true;
                                    for (Seat seat1 : room.getSeats()) {
                                        if (0 == seat1.getGrab()) {
                                            allGrab = false;
                                            break;
                                        }
                                    }
                                    if (allGrab) {
                                        room.compareGrab();
                                        room.setGameStatus(GameStatus.PLAYING);

                                        SanGong.ConfirmBankerResponse confirmBankerResponse = SanGong.ConfirmBankerResponse.newBuilder()
                                                .setBanker(room.getGrab()).build();
                                        response.setOperationType(GameBase.OperationType.CONFIRM_BANKER).setData(confirmBankerResponse.toByteString());
                                        room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                            SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                                        });
                                    }
                                });
                                break;
                            case PLAY_SCORE:
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
                                        if (0 == seat1.getPlayScore()) {
                                            allPlay = false;
                                            break;
                                        }
                                    }
                                    if (allPlay) {
                                        room.dealCard();
                                        SanGong.DealCard.Builder dealCard = SanGong.DealCard.newBuilder();
                                        response.setOperationType(GameBase.OperationType.DEAL_CARD);
                                        room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                                            dealCard.clearCards();
                                            dealCard.addAllCards(seat1.getCards());
                                            response.setData(dealCard.build().toByteString());
                                            SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                                        });
                                    }

                                });
                                break;

                            case OPEN_CARD:
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
                                        if (seat1.isOpen()) {
                                            allOpen = false;
                                            break;
                                        }
                                    }
                                    if (allOpen) {
                                        room.gameOver(response, redisService);
                                    }

                                });
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
                        Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
                        response.setOperationType(GameBase.OperationType.DISSOLVE).clearData();
                        for (Seat seat : room.getSeats()) {
                            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                messageReceive.send(response.build(), seat.getUserId());
                            }
                        }
                        room.roomOver(response, redisService);
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