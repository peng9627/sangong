package sangong.mode;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.slf4j.LoggerFactory;
import sangong.constant.Constant;
import sangong.entrance.SanGongTcpService;
import sangong.redis.RedisService;
import sangong.timout.OpenTimeout;
import sangong.timout.PlayTimeout;
import sangong.timout.ReadyTimeout;
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
    private List<Integer> seatNos;
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

    public List<Integer> getSeatNos() {
        return seatNos;
    }

    public void setSeatNos(List<Integer> seatNos) {
        this.seatNos = seatNos;
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

    public void addSeat(User user, int score) {
        Seat seat = new Seat();
        seat.setRobot(false);
        seat.setReady(false);
        seat.setAreaString(user.getArea());
        seat.setScore(score);
        seat.setUserId(user.getUserId());
        seat.setHead(user.getHead());
        seat.setNickname(user.getNickname());
        seat.setSex(user.getSex().equals("1"));
        seat.setSeatNo(seatNos.get(0));
        seat.setIp(user.getLastLoginIp());
        seat.setGamecount(user.getGameCount());
        seatNos.remove(0);
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
            boolean bankerIsSangong = Card.isSanGong(grabSeat.getCards());
            int bankScore = 0;
            for (Seat seat : seats) {
                boolean isSangong = Card.isSanGong(seat.getCards());
                if (isSangong) {
                    seat.setSanGongCount(seat.getSanGongCount() + 1);
                }
                if (seat.getSeatNo() != grabSeat.getSeatNo()) {
                    if (Card.compare(grabSeat.getCards(), seat.getCards())) {
                        int lose = bankerIsSangong ? seat.getPlayScore() * 2 : seat.getPlayScore();

                        bankScore += lose;

                        SanGong.SanGongResult.Builder userResult = SanGong.SanGongResult.newBuilder();
                        userResult.setID(seat.getUserId());
                        userResult.addAllCards(seat.getCards());
                        seat.setScore(seat.getScore() - lose);
                        userResult.setCurrentScore(-lose);
                        userResult.setTotalScore(seat.getScore());
                        userResult.setCardType(isSangong ? SanGong.CardType.CARDTYPE_SANGONG : SanGong.CardType.CARDTYPE_DANPAI);
                        resultResponse.addResult(userResult);

                        SeatRecord seatRecord = new SeatRecord();
                        seatRecord.setUserId(seat.getUserId());
                        seatRecord.setNickname(seat.getNickname());
                        seatRecord.setHead(seat.getHead());
                        seatRecord.getCards().addAll(seat.getCards());
                        seatRecord.setWinOrLose(-lose);
                        seatRecords.add(seatRecord);
                    } else {
                        int win = isSangong ? seat.getPlayScore() * 2 : seat.getPlayScore();
                        bankScore -= win;

                        SanGong.SanGongResult.Builder userResult = SanGong.SanGongResult.newBuilder();
                        userResult.setID(seat.getUserId());
                        userResult.addAllCards(seat.getCards());
                        seat.setScore(seat.getScore() + win);
                        userResult.setCurrentScore(win);
                        userResult.setTotalScore(seat.getScore());
                        userResult.setCardType(isSangong ? SanGong.CardType.CARDTYPE_SANGONG : SanGong.CardType.CARDTYPE_DANPAI);
                        resultResponse.addResult(userResult);

                        SeatRecord seatRecord = new SeatRecord();
                        seatRecord.setUserId(seat.getUserId());
                        seatRecord.setNickname(seat.getNickname());
                        seatRecord.setHead(seat.getHead());
                        seatRecord.getCards().addAll(seat.getCards());
                        seatRecord.setWinOrLose(win);
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
            seatRecord.getCards().addAll(grabSeat.getCards());
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
                    boolean isSangong = Card.isSanGong(lastWin.getCards());
                    int loseScore = isSangong ? lastWin.getPlayScore() * 2 : lastWin.getPlayScore();
                    loseTemp -= loseScore;
                    win.put(lastWin.getUserId(), loseScore);
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
                    seatRecord.getCards().addAll(seat.getCards());
                    seatRecord.setWinOrLose(-lose.get(seat.getUserId()));
                    seatRecords.add(seatRecord);

                } else if (win.containsKey(seat.getUserId())) {
                    seat.setScore(seat.getScore() + win.get(seat.getUserId()));
                    userResult.setCurrentScore(win.get(seat.getUserId()));
                    userResult.setTotalScore(seat.getScore());
                    userResult.setCardType(isSangong ? SanGong.CardType.CARDTYPE_SANGONG : SanGong.CardType.CARDTYPE_DANPAI);

                    resultResponse.addResult(userResult);

                    SeatRecord seatRecord = new SeatRecord();
                    seatRecord.setUserId(seat.getUserId());
                    seatRecord.setNickname(seat.getNickname());
                    seatRecord.setHead(seat.getHead());
                    seatRecord.getCards().addAll(seat.getCards());
                    seatRecord.setWinOrLose(win.get(seat.getUserId()));
                    seatRecords.add(seatRecord);
                }
            }

        }

        record.setSeatRecordList(seatRecords);
        record.getHistoryList().addAll(historyList);
        recordList.add(record);

        if (redisService.exists("room_match" + roomNo)) {
            GameBase.ScoreResponse.Builder scoreResponse = GameBase.ScoreResponse.newBuilder();
            for (SanGong.SanGongResult.Builder userResult : resultResponse.getResultBuilderList()) {
                if (SanGongTcpService.userClients.containsKey(userResult.getID())) {
                    GameBase.MatchResult matchResult;
                    if (gameCount != gameTimes) {
                        matchResult = GameBase.MatchResult.newBuilder().setResult(0).setCurrentScore(userResult.getCurrentScore())
                                .setTotalScore(userResult.getTotalScore()).build();
                    } else {
                        matchResult = GameBase.MatchResult.newBuilder().setResult(2).setCurrentScore(userResult.getCurrentScore())
                                .setTotalScore(userResult.getTotalScore()).build();
                    }
                    SanGongTcpService.userClients.get(userResult.getID()).send(response.setOperationType(GameBase.OperationType.MATCH_RESULT)
                            .setData(matchResult.toByteString()).build(), userResult.getID());
                }
                scoreResponse.addScoreResult(GameBase.ScoreResult.newBuilder().setID(userResult.getID()).setScore(userResult.getTotalScore()));
            }
            for (Seat seat : seats) {
                if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                    SanGongTcpService.userClients.get(seat.getUserId()).send(response.setOperationType(GameBase.OperationType.MATCH_SCORE)
                            .setData(scoreResponse.build().toByteString()).build(), seat.getUserId());
                }
            }
        } else {
            response.setOperationType(GameBase.OperationType.RESULT).setData(resultResponse.build().toByteString());
            seats.stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId()))
                    .forEach(seat -> SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId()));
        }
        clear();
        if (1 == gameCount && 2 == payType) {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("flowType", 2);
            if (10 == gameTimes) {
                jsonObject.put("money", 1);
            } else {
                jsonObject.put("money", 2);
            }
            jsonObject.put("description", "AA支付" + roomNo);
            for (Seat seat : seats) {
                jsonObject.put("userId", seat.getUserId());
                ApiResponse moneyDetail = JSON.parseObject(HttpUtil.urlConnectionByRsa(Constant.apiUrl + Constant.moneyDetailedCreate, jsonObject.toJSONString()), new TypeReference<ApiResponse<User>>() {
                });
                if (0 != moneyDetail.getCode()) {
                    LoggerFactory.getLogger(this.getClass()).error(Constant.apiUrl + Constant.moneyDetailedCreate + "?" + jsonObject.toJSONString());
                }
            }
        }
        //结束房间
        if (gameCount == gameTimes) {
            roomOver(response, redisService);
        } else {
            if (redisService.exists("room_match" + roomNo)) {
                new ReadyTimeout(Integer.valueOf(roomNo), redisService, gameCount).start();
            }
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
        JSONObject jsonObject = new JSONObject();
        //是否竞技场
        if (redisService.exists("room_match" + roomNo)) {
            String matchNo = redisService.getCache("room_match" + roomNo);
            redisService.delete("room_match" + roomNo);
            if (redisService.exists("match_info" + matchNo)) {
                while (!redisService.lock("lock_match_info" + matchNo)) {
                }
                GameBase.MatchResult.Builder matchResult = GameBase.MatchResult.newBuilder();
                MatchInfo matchInfo = JSON.parseObject(redisService.getCache("match_info" + matchNo), MatchInfo.class);
                Arena arena = matchInfo.getArena();

                //移出当前桌
                List<Integer> rooms = matchInfo.getRooms();
                for (Integer integer : rooms) {
                    if (integer == Integer.parseInt(roomNo)) {
                        rooms.remove(integer);
                        break;
                    }
                }

                //等待的人
                List<MatchUser> waitUsers = matchInfo.getWaitUsers();
                if (null == waitUsers) {
                    waitUsers = new ArrayList<>();
                    matchInfo.setWaitUsers(waitUsers);
                }
                //在比赛中的人 重置分数
                List<MatchUser> matchUsers = matchInfo.getMatchUsers();
                for (Seat seat : seats) {
                    redisService.delete("reconnect" + seat.getUserId());
                    for (MatchUser matchUser : matchUsers) {
                        if (seat.getUserId() == matchUser.getUserId()) {
                            matchUser.setScore(seat.getScore());
                        }
                    }
//                    if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
//                        SanGongTcpService.userClients.get(seat.getUserId()).send(response.setOperationType(GameBase.OperationType.ROOM_INFO).clearData().build(), seat.getUserId());
//                        GameBase.RoomSeatsInfo.Builder roomSeatsInfo = GameBase.RoomSeatsInfo.newBuilder();
//                        GameBase.SeatResponse.Builder seatResponse = GameBase.SeatResponse.newBuilder();
//                        seatResponse.setSeatNo(1);
//                        seatResponse.setID(seat.getUserId());
//                        seatResponse.setScore(seat.getScore());
//                        seatResponse.setReady(false);
//                        seatResponse.setIp(seat.getIp());
//                        seatResponse.setGameCount(seat.getGamecount());
//                        seatResponse.setNickname(seat.getNickname());
//                        seatResponse.setHead(seat.getHead());
//                        seatResponse.setSex(seat.isSex());
//                        seatResponse.setOffline(false);
//                        seatResponse.setIsRobot(false);
//                        roomSeatsInfo.addSeats(seatResponse.build());
//                        SanGongTcpService.userClients.get(seat.getUserId()).send(response.setOperationType(GameBase.OperationType.SEAT_INFO).setData(roomSeatsInfo.build().toByteString()).build(), seat.getUserId());
//                    }
                }

                //用户对应分数
                Map<Integer, Integer> userIdScore = new HashMap<>();
                for (MatchUser matchUser : matchUsers) {
                    userIdScore.put(matchUser.getUserId(), matchUser.getScore());
                }

                GameBase.MatchData.Builder matchData = GameBase.MatchData.newBuilder();
                switch (matchInfo.getStatus()) {
                    case 1:

                        //根据金币排序
                        seats.sort(new Comparator<Seat>() {
                            @Override
                            public int compare(Seat o1, Seat o2) {
                                return o1.getScore() > o2.getScore() ? 1 : -1;
                            }
                        });

                        //本局未被淘汰的
                        List<MatchUser> thisWait = new ArrayList<>();
                        //循环座位，淘汰
                        for (Seat seat : seats) {
                            for (MatchUser matchUser : matchUsers) {
                                if (matchUser.getUserId() == seat.getUserId()) {
                                    if (seat.getScore() < matchInfo.getMatchEliminateScore() && matchUsers.size() > arena.getCount() / 2) {
                                        matchUsers.remove(matchUser);

                                        matchResult.setResult(3).setTotalScore(seat.getScore()).setCurrentScore(-1);
                                        response.setOperationType(GameBase.OperationType.MATCH_RESULT).setData(matchResult.build().toByteString());
                                        if (SanGongTcpService.userClients.containsKey(matchUser.getUserId())) {
                                            SanGongTcpService.userClients.get(matchUser.getUserId()).send(response.build(), matchUser.getUserId());
                                        }
                                        response.setOperationType(GameBase.OperationType.MATCH_BALANCE).setData(GameBase.MatchBalance.newBuilder()
                                                .setRanking(matchUsers.size()).setTotalScore(matchUser.getScore()).build().toByteString());
                                        if (SanGongTcpService.userClients.containsKey(matchUser.getUserId())) {
                                            SanGongTcpService.userClients.get(matchUser.getUserId()).send(response.build(), matchUser.getUserId());
                                            GameBase.OverResponse.Builder over = GameBase.OverResponse.newBuilder();
                                            String uuid = UUID.randomUUID().toString().replace("-", "");
                                            while (redisService.exists(uuid)) {
                                                uuid = UUID.randomUUID().toString().replace("-", "");
                                            }
                                            redisService.addCache("backkey" + uuid, seat.getUserId() + "", 1800);
                                            over.setBackKey(uuid);
                                            over.setDateTime(new Date().getTime());
                                            response.setOperationType(GameBase.OperationType.OVER).setData(over.build().toByteString());
                                            SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
                                        }

                                        redisService.delete("reconnect" + seat.getUserId());
                                    } else {
                                        thisWait.add(matchUser);
                                        redisService.addCache("reconnect" + seat.getUserId(), "sangong," + matchNo);
                                    }
                                    break;
                                }
                            }
                        }

                        //淘汰人数以满
                        int count = matchUsers.size();
                        if (count == arena.getCount() / 2 && 0 == rooms.size()) {
                            waitUsers.clear();
                            List<User> users = new ArrayList<>();
                            StringBuilder stringBuilder = new StringBuilder();
                            for (MatchUser matchUser : matchUsers) {
                                stringBuilder.append(",").append(matchUser.getUserId());
                            }
                            jsonObject.clear();
                            jsonObject.put("userIds", stringBuilder.toString().substring(1));
                            ApiResponse<List<User>> usersResponse = JSON.parseObject(HttpUtil.urlConnectionByRsa(Constant.apiUrl + Constant.userListUrl, jsonObject.toJSONString()),
                                    new TypeReference<ApiResponse<List<User>>>() {
                                    });
                            if (0 == usersResponse.getCode()) {
                                users = usersResponse.getData();
                            }

                            //第二轮开始
                            matchInfo.setStatus(2);
                            matchData.setStatus(2);
                            matchData.setCurrentCount(matchUsers.size());
                            matchData.setRound(1);
                            while (4 <= users.size()) {
                                rooms.add(matchInfo.addRoom(matchNo, 2, redisService, users.subList(0, 4), userIdScore, response, matchData));
                            }
                        } else if (count > arena.getCount() / 2) {
                            //满四人继续匹配
                            waitUsers.addAll(thisWait);
                            while (4 <= waitUsers.size()) {
                                //剩余用户
                                List<User> users = new ArrayList<>();
                                StringBuilder stringBuilder = new StringBuilder();
                                for (int i = 0; i < 4; i++) {
                                    stringBuilder.append(",").append(waitUsers.remove(0).getUserId());
                                }
                                jsonObject.clear();
                                jsonObject.put("userIds", stringBuilder.toString().substring(1));
                                ApiResponse<List<User>> usersResponse = JSON.parseObject(HttpUtil.urlConnectionByRsa(Constant.apiUrl + Constant.userListUrl, jsonObject.toJSONString()),
                                        new TypeReference<ApiResponse<List<User>>>() {
                                        });
                                if (0 == usersResponse.getCode()) {
                                    users = usersResponse.getData();
                                }
                                matchData.setStatus(1);
                                matchData.setCurrentCount(matchUsers.size());
                                matchData.setRound(1);
                                rooms.add(matchInfo.addRoom(matchNo, 1, redisService, users, userIdScore, response, matchData));
                            }
                        }
                        break;
                    case 2:
                    case 3:
                        for (Seat seat : seats) {
                            matchResult.setResult(2);
                            response.setOperationType(GameBase.OperationType.MATCH_RESULT).setData(matchResult.build().toByteString());
                            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                                SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
                            }
                            redisService.addCache("reconnect" + seat.getUserId(), "sangong," + matchNo);
                        }
                        if (0 == rooms.size()) {
                            matchInfo.setStatus(matchInfo.getStatus() + 1);
                            matchData.setStatus(2);

                            List<User> users = new ArrayList<>();
                            StringBuilder stringBuilder = new StringBuilder();
                            for (MatchUser matchUser : matchUsers) {
                                stringBuilder.append(",").append(matchUser.getUserId());
                            }
                            jsonObject.clear();
                            jsonObject.put("userIds", stringBuilder.toString().substring(1));
                            ApiResponse<List<User>> usersResponse = JSON.parseObject(HttpUtil.urlConnectionByRsa(Constant.apiUrl + Constant.userListUrl, jsonObject.toJSONString()),
                                    new TypeReference<ApiResponse<List<User>>>() {
                                    });
                            if (0 == usersResponse.getCode()) {
                                users = usersResponse.getData();
                            }
                            matchData.setCurrentCount(matchUsers.size());
                            matchData.setRound(matchInfo.getStatus() - 1);
                            while (4 <= users.size()) {
                                rooms.add(matchInfo.addRoom(matchNo, 2, redisService, users.subList(0, 4), userIdScore, response, matchData));
                            }
                        }
                        break;
                    case 4:
                        for (Seat seat : seats) {
                            MatchUser matchUser = new MatchUser();
                            matchUser.setUserId(seat.getUserId());
                            matchUser.setScore(seat.getScore());
                            waitUsers.add(matchUser);
                            redisService.addCache("reconnect" + seat.getUserId(), "sangong," + matchNo);
                        }

                        waitUsers.sort(new Comparator<MatchUser>() {
                            @Override
                            public int compare(MatchUser o1, MatchUser o2) {
                                return o1.getScore() > o2.getScore() ? -1 : 1;
                            }
                        });
                        while (waitUsers.size() > 4) {
                            MatchUser matchUser = waitUsers.remove(waitUsers.size() - 1);
                            matchUsers.remove(matchUser);

                            response.setOperationType(GameBase.OperationType.MATCH_BALANCE).setData(GameBase.MatchBalance.newBuilder()
                                    .setRanking(matchUsers.size()).setTotalScore(matchUser.getScore()).build().toByteString());
                            if (SanGongTcpService.userClients.containsKey(matchUser.getUserId())) {
                                SanGongTcpService.userClients.get(matchUser.getUserId()).send(response.build(), matchUser.getUserId());
                                GameBase.OverResponse.Builder over = GameBase.OverResponse.newBuilder();
                                String uuid = UUID.randomUUID().toString().replace("-", "");
                                while (redisService.exists(uuid)) {
                                    uuid = UUID.randomUUID().toString().replace("-", "");
                                }
                                redisService.addCache("backkey" + uuid, matchUser.getUserId() + "", 1800);
                                over.setBackKey(uuid);
                                over.setDateTime(new Date().getTime());
                                response.setOperationType(GameBase.OperationType.OVER).setData(over.build().toByteString());
                                SanGongTcpService.userClients.get(matchUser.getUserId()).send(response.build(), matchUser.getUserId());
                            }
                            redisService.delete("reconnect" + matchUser.getUserId());
                        }

                        if (0 == rooms.size()) {

                            matchUsers.clear();
                            matchUsers.addAll(waitUsers);
                            waitUsers.clear();

                            matchInfo.setStatus(5);
                            matchData.setStatus(3);

                            List<User> users = new ArrayList<>();
                            StringBuilder stringBuilder = new StringBuilder();
                            for (MatchUser matchUser : matchUsers) {
                                stringBuilder.append(",").append(matchUser.getUserId());
                            }
                            jsonObject.clear();
                            jsonObject.put("userIds", stringBuilder.toString().substring(1));
                            ApiResponse<List<User>> usersResponse = JSON.parseObject(HttpUtil.urlConnectionByRsa(Constant.apiUrl + Constant.userListUrl, jsonObject.toJSONString()),
                                    new TypeReference<ApiResponse<List<User>>>() {
                                    });
                            if (0 == usersResponse.getCode()) {
                                users = usersResponse.getData();
                            }
                            matchData.setCurrentCount(matchUsers.size());
                            matchData.setRound(1);
                            while (4 == users.size()) {
                                rooms.add(matchInfo.addRoom(matchNo, 2, redisService, users, userIdScore, response, matchData));
                            }
                        }
                        break;
                    case 5:
                        matchUsers.sort(new Comparator<MatchUser>() {
                            @Override
                            public int compare(MatchUser o1, MatchUser o2) {
                                return o1.getScore() > o2.getScore() ? -1 : 1;
                            }
                        });
                        for (int i = 0; i < matchUsers.size(); i++) {
                            if (i == 0 && matchInfo.getArena().getArenaType() == 0) {
                                jsonObject.clear();
                                jsonObject.put("flowType", 1);
                                jsonObject.put("money", matchInfo.getArena().getReward());
                                jsonObject.put("description", "比赛获胜" + matchInfo.getArena().getId());
                                jsonObject.put("userId", matchUsers.get(i).getUserId());
                                ApiResponse moneyDetail = JSON.parseObject(HttpUtil.urlConnectionByRsa(Constant.apiUrl + Constant.moneyDetailedCreate, jsonObject.toJSONString()), new TypeReference<ApiResponse<User>>() {
                                });
                                if (0 != moneyDetail.getCode()) {
                                    LoggerFactory.getLogger(this.getClass()).error(Constant.apiUrl + Constant.moneyDetailedCreate + "?" + jsonObject.toJSONString());
                                }
                            }
                            matchResult.setResult(i == 0 ? 1 : 3).setTotalScore(matchUsers.get(i).getScore()).setCurrentScore(-1);
                            response.setOperationType(GameBase.OperationType.MATCH_RESULT).setData(matchResult.build().toByteString());
                            if (SanGongTcpService.userClients.containsKey(matchUsers.get(i).getUserId())) {
                                SanGongTcpService.userClients.get(matchUsers.get(i).getUserId()).send(response.build(), matchUsers.get(i).getUserId());
                            }
                            response.setOperationType(GameBase.OperationType.MATCH_BALANCE).setData(GameBase.MatchBalance.newBuilder()
                                    .setRanking(i + 1).setTotalScore(matchUsers.get(i).getScore()).build().toByteString());
                            if (SanGongTcpService.userClients.containsKey(matchUsers.get(i).getUserId())) {
                                SanGongTcpService.userClients.get(matchUsers.get(i).getUserId()).send(response.build(), matchUsers.get(i).getUserId());
                                GameBase.OverResponse.Builder over = GameBase.OverResponse.newBuilder();
                                String uuid = UUID.randomUUID().toString().replace("-", "");
                                while (redisService.exists(uuid)) {
                                    uuid = UUID.randomUUID().toString().replace("-", "");
                                }
                                redisService.addCache("backkey" + uuid, matchUsers.get(i).getUserId() + "", 1800);
                                over.setBackKey(uuid);
                                over.setDateTime(new Date().getTime());
                                response.setOperationType(GameBase.OperationType.OVER).setData(over.build().toByteString());
                                SanGongTcpService.userClients.get(matchUsers.get(i).getUserId()).send(response.build(), matchUsers.get(i).getUserId());
                            }
                        }
                        matchInfo.setStatus(-1);
                        break;
                }

                if (0 < matchInfo.getStatus()) {
                    matchInfo.setMatchUsers(matchUsers);
                    matchInfo.setRooms(rooms);
                    matchInfo.setWaitUsers(waitUsers);
                    redisService.addCache("match_info" + matchNo, JSON.toJSONString(matchInfo));
                }
                redisService.unlock("lock_match_info" + matchNo);
            }
        } else {
            if (0 == gameStatus.compareTo(GameStatus.WAITING)) {
                if (1 == payType) {
                    jsonObject.put("flowType", 1);
                    if (10 == gameTimes) {
                        jsonObject.put("money", 3);
                    } else {
                        jsonObject.put("money", 6);
                    }
                    jsonObject.put("description", "开房间退回" + roomNo);
                    jsonObject.put("userId", roomOwner);
                    ApiResponse moneyDetail = JSON.parseObject(HttpUtil.urlConnectionByRsa(Constant.apiUrl + Constant.moneyDetailedCreate, jsonObject.toJSONString()), new TypeReference<ApiResponse<User>>() {
                    });
                    if (0 != moneyDetail.getCode()) {
                        LoggerFactory.getLogger(this.getClass()).error(Constant.apiUrl + Constant.moneyDetailedCreate + "?" + jsonObject.toJSONString());
                    }
                }
            }
            StringBuilder people = new StringBuilder();
            if (0 != recordList.size()) {
                SanGong.SanGongBalanceResponse.Builder balance = SanGong.SanGongBalanceResponse.newBuilder();

                for (Seat seat : seats) {
                    SanGong.SanGongSeatBalance.Builder seatGameBalance = SanGong.SanGongSeatBalance.newBuilder()
                            .setID(seat.getUserId()).setSanGongCount(seat.getSanGongCount()).setWinOrLose(seat.getScore());
                    balance.addGameBalance(seatGameBalance);
                }

                for (Seat seat : seats) {
                    redisService.delete("reconnect" + seat.getUserId());
                    if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                        response.setOperationType(GameBase.OperationType.BALANCE).setData(balance.build().toByteString());
                        SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
                    }
                }
            }
            GameBase.OverResponse.Builder over = GameBase.OverResponse.newBuilder();
            for (Seat seat : seats) {
                redisService.delete("reconnect" + seat.getUserId());
                if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                    String uuid = UUID.randomUUID().toString().replace("-", "");
                    while (redisService.exists(uuid)) {
                        uuid = UUID.randomUUID().toString().replace("-", "");
                    }
                    redisService.addCache("backkey" + uuid, seat.getUserId() + "", 1800);
                    over.setBackKey(uuid);
                    over.setDateTime(new Date().getTime());
                    response.setOperationType(GameBase.OperationType.OVER).setData(over.build().toByteString());
                    SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
                }
            }

            if (0 != recordList.size()) {
                List<TotalScore> totalScores = new ArrayList<>();
                for (Seat seat : seats) {
                    people.append(",").append(seat.getUserId());
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
                jsonObject.clear();
                jsonObject.put("gameType", 3);
                jsonObject.put("roomOwner", roomOwner);
                jsonObject.put("people", people.toString().substring(1));
                jsonObject.put("gameTotal", gameTimes);
                jsonObject.put("gameCount", gameCount);
                jsonObject.put("peopleCount", seats.size());
                jsonObject.put("roomNo", Integer.parseInt(roomNo));
                JSONObject gameRule = new JSONObject();
                gameRule.put("bankerWay", bankerWay);
                gameRule.put("payType", payType);
                jsonObject.put("gameRule", gameRule.toJSONString());
                jsonObject.put("gameData", JSON.toJSONString(recordList, feature, features).getBytes());
                jsonObject.put("scoreData", JSON.toJSONString(totalScores, feature, features).getBytes());

                ApiResponse apiResponse = JSON.parseObject(HttpUtil.urlConnectionByRsa(Constant.apiUrl + Constant.gamerecordCreateUrl, jsonObject.toJSONString()), ApiResponse.class);
                if (0 != apiResponse.getCode()) {
                    LoggerFactory.getLogger(this.getClass()).error(Constant.apiUrl + Constant.gamerecordCreateUrl + "?" + jsonObject.toJSONString());
                }
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
        intoResponseBuilder.setGameType(GameBase.GameType.SANGONG);
        intoResponseBuilder.setStarted(0 != gameCount);

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
            seatResponse.setIsRobot(seat1.isRobot());
            roomSeatsInfo.addSeats(seatResponse.build());
        }
        response.setOperationType(GameBase.OperationType.SEAT_INFO).setData(roomSeatsInfo.build().toByteString());
        for (Seat seat : seats) {
            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
            }
        }
    }

    public void start(GameBase.BaseConnection.Builder response, RedisService redisService) {
        SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
        if (0 == gameStatus.compareTo(GameStatus.WAITING)) {
            statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
            statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_READYING).build();
            response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
            seats.stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
                SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
            });
        }
        for (Seat seat : seats) {
            seat.setReady(false);
        }
        gameCount += 1;
        gameStatus = GameStatus.PLAYING;
        statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
        statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
        statusDate = new Date();
        statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_PLAYING).build();
        response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
        seats.stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId())).forEach(seat -> {
            SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
        });
        new PlayTimeout(Integer.valueOf(roomNo), redisService, statusDate).start();
    }

    public void allPlay(GameBase.BaseConnection.Builder response, RedisService redisService) {
        dealCard();
        gameStatus = GameStatus.OPENING;
        SanGong.DealCard.Builder dealCard = SanGong.DealCard.newBuilder();
        response.setOperationType(GameBase.OperationType.DEAL_CARD);
        seats.stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
            dealCard.clearCards();
            dealCard.addAllCards(seat1.getCards());
            response.setData(dealCard.build().toByteString());
            SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
        });

        SanGong.SangongGameStatusResponse.Builder statusResponse = SanGong.SangongGameStatusResponse.newBuilder();
        statusResponse.setTimeCounter(redisService.exists("room_match" + roomNo) ? 8 : 0);
        statusDate = new Date();
        statusResponse.setGameStatus(SanGong.SangongGameStatus.SANGONG_OPENING).build();
        response.setOperationType(GameBase.OperationType.UPDATE_STATUS).setData(statusResponse.build().toByteString());
        seats.stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
            SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
        });
        new OpenTimeout(Integer.valueOf(roomNo), redisService, statusDate).start();
    }
}
