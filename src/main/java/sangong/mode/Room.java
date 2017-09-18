package sangong.mode;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.slf4j.LoggerFactory;
import sangong.constant.Constant;
import sangong.entrance.SanGongTcpService;
import sangong.redis.RedisService;
import sangong.utils.HttpUtil;

import java.util.*;

/**
 * Author pengyi
 * Date 17-3-7.
 */
public class Room {

    private int baseScore; //基础分
    private String roomNo;  //桌号
    private List<Seat> seats = new ArrayList<>();//座位
    private List<OperationHistory> historyList = new ArrayList<>();
    private GameStatus gameStatus;
    private int gameTimes; //游戏局数
    private int grab;//庄家
    private int gameCount;
    private int bankerWay;//庄家方式
    private List<Record> recordList = new ArrayList<>();//战绩
    private int roomOwner;
    private Date startDate;
    private Date statusDate;
    private int payType;//支付方式
    private int count;//人数

    public int getBaseScore() {
        return baseScore;
    }

    public void setBaseScore(int baseScore) {
        this.baseScore = baseScore;
    }

    public String getRoomNo() {
        return roomNo;
    }

    public void setRoomNo(String roomNo) {
        this.roomNo = roomNo;
    }

    public List<Seat> getSeats() {
        return seats;
    }

    public void setSeats(List<Seat> seats) {
        this.seats = seats;
    }

    public List<OperationHistory> getHistoryList() {
        return historyList;
    }

    public void setHistoryList(List<OperationHistory> historyList) {
        this.historyList = historyList;
    }

    public GameStatus getGameStatus() {
        return gameStatus;
    }

    public void setGameStatus(GameStatus gameStatus) {
        this.gameStatus = gameStatus;
    }

    public int getGameTimes() {
        return gameTimes;
    }

    public void setGameTimes(int gameTimes) {
        this.gameTimes = gameTimes;
    }

    public int getGrab() {
        return grab;
    }

    public void setGrab(int grab) {
        this.grab = grab;
    }

    public int getGameCount() {
        return gameCount;
    }

    public void setGameCount(int gameCount) {
        this.gameCount = gameCount;
    }

    public int getBankerWay() {
        return bankerWay;
    }

    public void setBankerWay(int bankerWay) {
        this.bankerWay = bankerWay;
    }

    public List<Record> getRecordList() {
        return recordList;
    }

    public void setRecordList(List<Record> recordList) {
        this.recordList = recordList;
    }

    public int getRoomOwner() {
        return roomOwner;
    }

    public void setRoomOwner(int roomOwner) {
        this.roomOwner = roomOwner;
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public Date getStatusDate() {
        return statusDate;
    }

    public void setStatusDate(Date statusDate) {
        this.statusDate = statusDate;
    }

    public int getPayType() {
        return payType;
    }

    public void setPayType(int payType) {
        this.payType = payType;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public void addSeat(User user) {
        Seat seat = new Seat();
        seat.setRobot(false);
        seat.setReady(false);
        seat.setAreaString(user.getArea());
        seat.setScore(0);
        seat.setSeatNo(seats.size() + 1);
        seat.setUserId(user.getUserId());
        seat.setHead(user.getHead());
        seat.setNickname(user.getNickname());
        seat.setSex(user.getSex().equals("MAN"));
        seat.setIp(user.getLastLoginIp());
        seat.setGamecount(user.getGameCount());
        seats.add(seat);
    }

    public void dealCard() {
        startDate = new Date();
        int min = Card.getAllCard().size();
        List<Integer> surplusCards = Card.getAllCard();
        for (Seat seat : seats) {
            List<Integer> cardList = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int cardIndex = (int) (Math.random() * surplusCards.size());
                if (cardIndex < min) {
                    min = cardIndex;
                }
                cardList.add(surplusCards.get(cardIndex));
                surplusCards.remove(cardIndex);
            }
            seat.setCards(cardList);
        }
    }

//    public void compareGrab(int userId) {
//        if (1 == bankerWay) {
////            int userId = 0;
////            int score = Integer.MIN_VALUE;
////
////            for (Seat seat : seats) {
////                if (1 == seat.getGrab() && seat.getScore() > score) {
////                    userId = seat.getUserId();
////                    score = seat.getScore();
////                }
////            }
////            grab = userId;
//            grab = userId;
//        } else {
//            if (0 == grab) {
//                grab = seats.get(0).getUserId();
//                return;
//            }
//            for (int i = 0; i < seats.size(); i++) {
//                if (seats.get(i).getUserId() == grab) {
//                    if (i == seats.size() - 1) {
//                        grab = seats.get(0).getUserId();
//                    } else {
//                        grab = seats.get(i + 1).getUserId();
//                    }
//                    break;
//                }
//            }
//        }
//        for (Seat seat : seats) {
//            seat.setGrab(0);
//        }
//    }

    private void clear() {
        historyList.clear();
        gameStatus = GameStatus.READYING;
        startDate = new Date();
        seats.forEach(Seat::clear);
    }

    /**
     * 游戏结束
     *
     * @param response
     * @param redisService
     */
    public void gameOver(GameBase.BaseConnection.Builder response, RedisService redisService) {

        Record record = new Record();
        record.setBanker(grab);

        List<SeatRecord> seatRecords = new ArrayList<>();

        SanGong.SanGongResultResponse.Builder resultResponse = SanGong.SanGongResultResponse.newBuilder();
        resultResponse.setReadyTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
        if (grab != 0) {
            Seat grabSeat = null;
            for (Seat seat : seats) {
                if (seat.getUserId() == grab) {
                    grabSeat = seat;
                    break;
                }
            }
            int bankScore = 0;
            for (Seat seat : seats) {
                boolean isSangong = Card.isSanGong(seat.getCards());
                if (isSangong) {
                    seat.setSanGongCount(seat.getSanGongCount() + 1);
                }
                if (seat.getSeatNo() != grabSeat.getSeatNo()) {
                    if (Card.compare(grabSeat.getCards(), seat.getCards())) {
                        bankScore += seat.getPlayScore();

                        SanGong.SanGongResult.Builder userResult = SanGong.SanGongResult.newBuilder();
                        userResult.setID(seat.getUserId());
                        userResult.addAllCards(seat.getCards());
                        seat.setScore(seat.getScore() - seat.getPlayScore());
                        userResult.setCurrentScore(-seat.getPlayScore());
                        userResult.setTotalScore(seat.getScore());
                        userResult.setCardType(isSangong ? SanGong.CardType.CARDTYPE_SANGONG : SanGong.CardType.CARDTYPE_DANPAI);
                        resultResponse.addResult(userResult);

                        SeatRecord seatRecord = new SeatRecord();
                        seatRecord.setUserId(seat.getUserId());
                        seatRecord.setNickname(seat.getNickname());
                        seatRecord.setHead(seat.getHead());
                        seatRecord.setCards(seat.getCards());
                        seatRecord.setWinOrLose(-seat.getPlayScore());
                        seatRecords.add(seatRecord);
                    } else {
                        bankScore -= seat.getPlayScore();

                        SanGong.SanGongResult.Builder userResult = SanGong.SanGongResult.newBuilder();
                        userResult.setID(seat.getUserId());
                        userResult.addAllCards(seat.getCards());
                        seat.setScore(seat.getScore() + seat.getPlayScore());
                        userResult.setCurrentScore(seat.getPlayScore());
                        userResult.setTotalScore(seat.getScore());
                        userResult.setCardType(isSangong ? SanGong.CardType.CARDTYPE_SANGONG : SanGong.CardType.CARDTYPE_DANPAI);
                        resultResponse.addResult(userResult);

                        SeatRecord seatRecord = new SeatRecord();
                        seatRecord.setUserId(seat.getUserId());
                        seatRecord.setNickname(seat.getNickname());
                        seatRecord.setHead(seat.getHead());
                        seatRecord.setCards(seat.getCards());
                        seatRecord.setWinOrLose(seat.getPlayScore());
                        seatRecords.add(seatRecord);
                    }
                }
            }

            SanGong.SanGongResult.Builder userResult = SanGong.SanGongResult.newBuilder();
            userResult.setID(grabSeat.getUserId());
            userResult.addAllCards(grabSeat.getCards());
            grabSeat.setScore(grabSeat.getScore() + bankScore);
            userResult.setCurrentScore(bankScore);
            userResult.setTotalScore(grabSeat.getScore());
            resultResponse.addResult(userResult);

            SeatRecord seatRecord = new SeatRecord();
            seatRecord.setUserId(grabSeat.getUserId());
            seatRecord.setNickname(grabSeat.getNickname());
            seatRecord.setHead(grabSeat.getHead());
            seatRecord.setCards(grabSeat.getCards());
            seatRecord.setWinOrLose(bankScore);
            seatRecords.add(seatRecord);
        } else {

            List<Seat> arraySeat = new ArrayList<>();
            arraySeat.addAll(seats);
            arraySeat.sort(new Comparator<Seat>() {
                @Override
                public int compare(Seat o1, Seat o2) {
                    if (Card.compare(o1.getCards(), o2.getCards())) {
                        return 1;
                    }
                    return -1;
                }
            });
            Map<Integer, Integer> lose = new HashMap<>();
            Map<Integer, Integer> win = new HashMap<>();
            Seat lastWin = null;
            Seat lastLose = null;
            List<Seat> userSeats = new ArrayList<>();
            userSeats.addAll(arraySeat);
            int loseTemp = 0;
            while (userSeats.size() > 0) {
                if (loseTemp >= 0) {
                    lastWin = userSeats.remove(userSeats.size() - 1);
                    loseTemp -= lastWin.getPlayScore();
                    win.put(lastWin.getUserId(), lastWin.getPlayScore());
                } else {
                    if (userSeats.size() > 0) {
                        lastLose = userSeats.remove(0);
                        loseTemp += lastLose.getPlayScore();
                        lose.put(lastLose.getUserId(), lastLose.getPlayScore());
                    }
                }
            }
            if (loseTemp > 0) {
                lose.put(lastLose.getUserId(), lose.get(lastLose.getUserId()) - loseTemp);
            } else {
                win.put(lastWin.getUserId(), win.get(lastWin.getUserId()) + loseTemp);
            }

            for (Seat seat : seats) {
                boolean isSangong = Card.isSanGong(seat.getCards());
                if (isSangong) {
                    seat.setSanGongCount(seat.getSanGongCount() + 1);
                }
                SanGong.SanGongResult.Builder userResult = SanGong.SanGongResult.newBuilder();
                userResult.setID(seat.getUserId());
                userResult.addAllCards(seat.getCards());
                if (lose.containsKey(seat.getUserId())) {
                    seat.setScore(seat.getScore() - lose.get(seat.getUserId()));
                    userResult.setCurrentScore(-lose.get(seat.getUserId()));
                    userResult.setTotalScore(seat.getScore());
                    userResult.setCardType(isSangong ? SanGong.CardType.CARDTYPE_SANGONG : SanGong.CardType.CARDTYPE_DANPAI);

                    resultResponse.addResult(userResult);

                    SeatRecord seatRecord = new SeatRecord();
                    seatRecord.setUserId(seat.getUserId());
                    seatRecord.setNickname(seat.getNickname());
                    seatRecord.setHead(seat.getHead());
                    seatRecord.setCards(seat.getCards());
                    seatRecord.setWinOrLose(-lose.get(seat.getUserId()));
                    seatRecords.add(seatRecord);

                } else if (win.containsKey(seat.getUserId())) {
                    seat.setScore(seat.getScore() + win.get(seat.getUserId()));
                    userResult.setCurrentScore(-win.get(seat.getUserId()));
                    userResult.setTotalScore(seat.getScore());
                    userResult.setCardType(isSangong ? SanGong.CardType.CARDTYPE_SANGONG : SanGong.CardType.CARDTYPE_DANPAI);

                    resultResponse.addResult(userResult);

                    SeatRecord seatRecord = new SeatRecord();
                    seatRecord.setUserId(seat.getUserId());
                    seatRecord.setNickname(seat.getNickname());
                    seatRecord.setHead(seat.getHead());
                    seatRecord.setCards(seat.getCards());
                    seatRecord.setWinOrLose(win.get(seat.getUserId()));
                    seatRecords.add(seatRecord);
                }
            }

        }

        record.setSeatRecordList(seatRecords);
        record.getHistoryList().addAll(historyList);
        recordList.add(record);

        response.setOperationType(GameBase.OperationType.RESULT).setData(resultResponse.build().toByteString());
        seats.stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId()))
                .forEach(seat -> SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId()));

        clear();
        if (1 == gameCount && 2 == payType) {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("flowType", 2);
            if (10 == gameCount) {
                jsonObject.put("money", 1);
            } else {
                jsonObject.put("money", 2);
            }
            jsonObject.put("description", "AA支付" + roomNo);
            for (Seat seat : seats) {
                jsonObject.put("userId", seat.getUserId());
                ApiResponse moneyDetail = JSON.parseObject(HttpUtil.urlConnectionByRsa("http://127.0.0.1:9999/api/money_detailed/create", jsonObject.toJSONString()), new TypeReference<ApiResponse<User>>() {
                });
                if (0 != moneyDetail.getCode()) {
                    LoggerFactory.getLogger(this.getClass()).error("http://127.0.0.1:9999/api/money_detailed/create?" + jsonObject.toJSONString());
                }
            }
        }
        //结束房间
        if (gameCount == gameTimes) {
            roomOver(response, redisService);
        } else {
            SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
            statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
            statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_READYING).build();
            response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
            seats.stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
                SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
            });
        }
    }

    public void roomOver(GameBase.BaseConnection.Builder response, RedisService redisService) {
        if (0 == gameStatus.compareTo(GameStatus.WAITING)) {
            if (1 == payType) {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("flowType", 1);
                if (10 == gameCount) {
                    jsonObject.put("money", 3);
                } else {
                    jsonObject.put("money", 6);
                }
                jsonObject.put("description", "开房间退回" + roomNo);
                jsonObject.put("userId", roomOwner);
                ApiResponse moneyDetail = JSON.parseObject(HttpUtil.urlConnectionByRsa("http://127.0.0.1:9999/api/money_detailed/create", jsonObject.toJSONString()), new TypeReference<ApiResponse<User>>() {
                });
                if (0 != moneyDetail.getCode()) {
                    LoggerFactory.getLogger(this.getClass()).error("http://127.0.0.1:9999/api/money_detailed/create?" + jsonObject.toJSONString());
                }
            }
        }
        SanGong.SanGongOverResponse.Builder over = SanGong.SanGongOverResponse.newBuilder();

        StringBuilder people = new StringBuilder();

        for (Seat seat : seats) {
            people.append(",").append(seat.getUserId());
            SanGong.SanGongSeatOver.Builder seatGameOver = SanGong.SanGongSeatOver.newBuilder()
                    .setID(seat.getUserId()).setSanGongCount(seat.getSanGongCount()).setWinOrLose(seat.getScore());
            over.addGameOver(seatGameOver);
        }

        for (Seat seat : seats) {
            redisService.delete("reconnect" + seat.getUserId());
            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                String uuid = UUID.randomUUID().toString().replace("-", "");
                while (redisService.exists(uuid)) {
                    uuid = UUID.randomUUID().toString().replace("-", "");
                }
                redisService.addCache("backkey" + uuid, seat.getUserId() + "", 1800);
                over.setBackKey(uuid);
                response.setOperationType(GameBase.OperationType.OVER).setData(over.build().toByteString());
                SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
            }
        }

        if (0 != recordList.size()) {
            List<TotalScore> totalScores = new ArrayList<>();
            for (Seat seat : seats) {
                TotalScore totalScore = new TotalScore();
                totalScore.setHead(seat.getHead());
                totalScore.setNickname(seat.getNickname());
                totalScore.setUserId(seat.getUserId());
                totalScore.setScore(seat.getScore());
                totalScores.add(totalScore);
            }
            SerializerFeature[] features = new SerializerFeature[]{SerializerFeature.WriteNullListAsEmpty,
                    SerializerFeature.WriteMapNullValue, SerializerFeature.DisableCircularReferenceDetect,
                    SerializerFeature.WriteNullStringAsEmpty, SerializerFeature.WriteNullNumberAsZero,
                    SerializerFeature.WriteNullBooleanAsFalse};
            int feature = SerializerFeature.config(JSON.DEFAULT_GENERATE_FEATURE, SerializerFeature.WriteEnumUsingName, false);
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("gameType", 3);
            jsonObject.put("roomOwner", roomOwner);
            jsonObject.put("people", people.toString().substring(1));
            jsonObject.put("gameTotal", gameTimes);
            jsonObject.put("gameCount", gameCount);
            jsonObject.put("peopleCount", seats.size());
            jsonObject.put("roomNo", Integer.parseInt(roomNo));
            jsonObject.put("gameData", JSON.toJSONString(recordList, feature, features).getBytes());
            jsonObject.put("scoreData", JSON.toJSONString(totalScores, feature, features).getBytes());

            ApiResponse apiResponse = JSON.parseObject(HttpUtil.urlConnectionByRsa(Constant.apiUrl + Constant.gamerecordCreateUrl, jsonObject.toJSONString()), ApiResponse.class);
            if (0 != apiResponse.getCode()) {
                LoggerFactory.getLogger(this.getClass()).error(Constant.apiUrl + Constant.gamerecordCreateUrl + "?" + jsonObject.toJSONString());
            }
        }

        //删除该桌
        redisService.delete("room" + roomNo);
        redisService.delete("room_type" + roomNo);
        roomNo = null;
    }

    public void sendRoomInfo(int userId, GameBase.RoomCardIntoResponse.Builder intoResponseBuilder, GameBase.BaseConnection.Builder response) {
        SanGong.SangongIntoResponse.Builder sangongIntoResponse = SanGong.SangongIntoResponse.newBuilder();
        sangongIntoResponse.setBaseScore(baseScore);
        sangongIntoResponse.setBankerWay(bankerWay);
        sangongIntoResponse.setGameTimes(gameTimes);
        sangongIntoResponse.setPayType(payType);
        sangongIntoResponse.setCount(count);
        intoResponseBuilder.setData(sangongIntoResponse.build().toByteString());

        response.setOperationType(GameBase.OperationType.ROOM_INFO).setData(intoResponseBuilder.build().toByteString());
        if (SanGongTcpService.userClients.containsKey(userId)) {
            SanGongTcpService.userClients.get(userId).send(response.build(), userId);
        }

    }

    public void sendSeatInfo(GameBase.BaseConnection.Builder response) {
        GameBase.RoomSeatsInfo.Builder roomSeatsInfo = GameBase.RoomSeatsInfo.newBuilder();
        for (Seat seat1 : seats) {
            GameBase.SeatResponse.Builder seatResponse = GameBase.SeatResponse.newBuilder();
            seatResponse.setSeatNo(seat1.getSeatNo());
            seatResponse.setID(seat1.getUserId());
            seatResponse.setScore(seat1.getScore());
            seatResponse.setReady(seat1.isReady());
            seatResponse.setIp(seat1.getIp());
            seatResponse.setGameCount(seat1.getGamecount());
            seatResponse.setNickname(seat1.getNickname());
            seatResponse.setHead(seat1.getHead());
            seatResponse.setSex(seat1.isSex());
            seatResponse.setOffline(seat1.isRobot());
            roomSeatsInfo.addSeats(seatResponse.build());
        }
        response.setOperationType(GameBase.OperationType.SEAT_INFO).setData(roomSeatsInfo.build().toByteString());
        for (Seat seat : seats) {
            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
            }
        }
    }
}
