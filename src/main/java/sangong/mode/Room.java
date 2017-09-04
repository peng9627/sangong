package sangong.mode;


import sangong.entrance.SanGongTcpService;
import sangong.redis.RedisService;

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
        seats.add(seat);
    }

    public void dealCard() {
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

    public void compareGrab() {
        if (1 == bankerWay) {
            int userId = 0;
            int score = Integer.MIN_VALUE;

            for (Seat seat : seats) {
                if (1 == seat.getGrab() && seat.getScore() > score) {
                    userId = seat.getUserId();
                    score = seat.getScore();
                }
            }
            grab = userId;
        } else {
            if (0 == grab) {
                grab = seats.get(0).getUserId();
                return;
            }
            for (int i = 0; i < seats.size(); i++) {
                if (seats.get(i).getUserId() == grab) {
                    if (i == seats.size() - 1) {
                        grab = seats.get(0).getUserId();
                    } else {
                        grab = seats.get(i + 1).getUserId();
                    }
                }
            }
        }
    }

    private void clear() {
        historyList.clear();
        gameStatus = GameStatus.READYING;
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
        //TODO 扣款
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
                if (Card.isSanGong(seat.getCards())) {
                    seat.setSanGongCount(seat.getSanGongCount() + 1);
                }
                if (seat.getSeatNo() != grabSeat.getSeatNo()) {
                    if (Card.compare(grabSeat.getCards(), seat.getCards())) {
                        bankScore += seat.getPlayScore();

                        SanGong.SanGongResult.Builder userResult = SanGong.SanGongResult.newBuilder();
                        userResult.setID(seat.getUserId());
                        userResult.addAllCards(seat.getCards());
                        userResult.setScore(-seat.getPlayScore());
                        seat.setScore(seat.getScore() - seat.getPlayScore());
                        resultResponse.addResult(userResult);

                        SeatRecord seatRecord = new SeatRecord();
                        seatRecord.setUserId(seat.getUserId());
                        seatRecord.setCards(seat.getCards());
                        seatRecord.setWinOrLose(-seat.getPlayScore());
                        seatRecords.add(seatRecord);
                    } else {
                        bankScore -= seat.getPlayScore();

                        SanGong.SanGongResult.Builder userResult = SanGong.SanGongResult.newBuilder();
                        userResult.setID(seat.getUserId());
                        userResult.addAllCards(seat.getCards());
                        userResult.setScore(seat.getPlayScore());
                        seat.setScore(seat.getScore() + seat.getPlayScore());
                        resultResponse.addResult(userResult);

                        SeatRecord seatRecord = new SeatRecord();
                        seatRecord.setUserId(seat.getUserId());
                        seatRecord.setCards(seat.getCards());
                        seatRecord.setWinOrLose(seat.getPlayScore());
                        seatRecords.add(seatRecord);
                    }
                }
            }

            SanGong.SanGongResult.Builder userResult = SanGong.SanGongResult.newBuilder();
            userResult.setID(grabSeat.getUserId());
            userResult.addAllCards(grabSeat.getCards());
            userResult.setScore(bankScore);
            grabSeat.setScore(grabSeat.getScore() + bankScore);
            resultResponse.addResult(userResult);

            SeatRecord seatRecord = new SeatRecord();
            seatRecord.setUserId(grabSeat.getUserId());
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

            Seat winSeat = arraySeat.get(arraySeat.size() - 1);
            int win = winSeat.getPlayScore();

            SanGong.SanGongResult.Builder winResult = SanGong.SanGongResult.newBuilder();
            winResult.setID(winSeat.getUserId());
            winResult.addAllCards(winSeat.getCards());
            winResult.setScore(win);
            winSeat.setScore(winSeat.getScore() + win);
            resultResponse.addResult(winResult);

            SeatRecord winRecord = new SeatRecord();
            winRecord.setUserId(winSeat.getUserId());
            winRecord.setCards(winSeat.getCards());
            winRecord.setWinOrLose(win);
            seatRecords.add(winRecord);

            Map<Integer, Integer> lose = new HashMap<>();
            while (win > 0) {
                for (Seat seat : seats) {
                    if (seat.getUserId() != winSeat.getUserId()) {
                        int score;
                        if (win > seat.getPlayScore()) {
                            score = seat.getPlayScore();
                        } else {
                            score = win;
                        }
                        if (!lose.containsKey(seat.getUserId())) {
                            lose.put(seat.getUserId(), score);
                        } else {
                            lose.put(seat.getUserId(), lose.get(seat.getUserId()) + score);
                        }
                        win -= score;
                        if (0 == win) {
                            break;
                        }
                    }
                }
            }

            for (Seat seat : seats) {
                if (Card.isSanGong(seat.getCards())) {
                    seat.setSanGongCount(seat.getSanGongCount() + 1);
                }
                if (seat.getSeatNo() != winSeat.getSeatNo()) {
                    SanGong.SanGongResult.Builder userResult = SanGong.SanGongResult.newBuilder();
                    userResult.setID(seat.getUserId());
                    userResult.addAllCards(seat.getCards());
                    if (lose.containsKey(seat.getUserId())) {
                        userResult.setScore(-lose.get(seat.getUserId()));
                        seat.setScore(seat.getScore() - lose.get(seat.getUserId()));
                    }
                    resultResponse.addResult(userResult);

                    SeatRecord seatRecord = new SeatRecord();
                    seatRecord.setUserId(winSeat.getUserId());
                    seatRecord.setCards(winSeat.getCards());
                    seatRecord.setWinOrLose(-lose.get(seat.getUserId()));
                    seatRecords.add(seatRecord);
                }
            }

        }

        record.setSeatRecordList(seatRecords);
        record.setHistoryList(historyList);
        recordList.add(record);

        response.setOperationType(GameBase.OperationType.RESULT).setData(resultResponse.build().toByteString());
        seats.stream().filter(seat -> SanGongTcpService.userClients.containsKey(seat.getUserId()))
                .forEach(seat -> SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId()));

        clear();
        //结束房间
        if (gameCount == gameTimes) {
            roomOver(response, redisService);
        }
    }

    public void roomOver(GameBase.BaseConnection.Builder response, RedisService redisService) {
        SanGong.SanGongOverResponse.Builder over = SanGong.SanGongOverResponse.newBuilder();

        for (Seat seat : seats) {
            SanGong.SanGongSeatOver.Builder seatGameOver = SanGong.SanGongSeatOver.newBuilder()
                    .setID(seat.getUserId()).setSanGongCount(seat.getSanGongCount());
            over.addGameOver(seatGameOver);
        }

        for (Seat seat : seats) {
            redisService.delete("reconnect" + seat.getUserId());
            if (SanGongTcpService.userClients.containsKey(seat.getUserId())) {
                String uuid = UUID.randomUUID().toString().replace("-", "");
                while (redisService.exists(uuid)) {
                    uuid = UUID.randomUUID().toString().replace("-", "");
                }
                redisService.addCache("backkey" + uuid, seat.getUserId() + "", 10);
                over.setBackKey(uuid);
                response.setOperationType(GameBase.OperationType.OVER).setData(over.build().toByteString());
                SanGongTcpService.userClients.get(seat.getUserId()).send(response.build(), seat.getUserId());
            }
        }

        //删除该桌
        redisService.delete("room" + roomNo);
        redisService.delete("room_type" + roomNo);
        roomNo = null;
    }
}
