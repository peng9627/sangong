package sangong.timout;

import com.alibaba.fastjson.JSON;
import sangong.constant.Constant;
import sangong.entrance.SanGongTcpService;
import sangong.mode.*;
import sangong.redis.RedisService;

import java.util.Date;

/**
 * Created by pengyi
 * Date : 17-8-31.
 * desc:
 */
public class OpenTimeout extends Thread {

    private int roomNo;
    private RedisService redisService;
    private GameBase.BaseConnection.Builder response;
    private Date statusDate;

    public OpenTimeout(int roomNo, RedisService redisService, Date statusDate) {
        this.roomNo = roomNo;
        this.redisService = redisService;
        this.response = GameBase.BaseConnection.newBuilder();
        this.statusDate = statusDate;
    }

    @Override
    public void run() {
        synchronized (this) {
            try {
                wait(Constant.playTimeout);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (redisService.exists("room" + roomNo)) {
            while (!redisService.lock("lock_room" + roomNo)) {
            }
            Room room = JSON.parseObject(redisService.getCache("room" + roomNo), Room.class);
            if (0 == room.getGameStatus().compareTo(GameStatus.OPENING) && 0 == room.getStatusDate().compareTo(statusDate)) {
                boolean hasNoOpen = false;
                for (Seat seat : room.getSeats()) {
                    if (!seat.isOpen()) {
                        seat.setOpen(true);
                        hasNoOpen = true;
                        SanGong.OpenCardResponse openCardResponse = SanGong.OpenCardResponse.newBuilder()
                                .setID(seat.getUserId()).addAllCards(seat.getCards()).build();

                        response.setOperationType(GameBase.OperationType.ACTION).setData(
                                GameBase.BaseAction.newBuilder().setOperationId(GameBase.ActionId.OPEN_CARD)
                                        .setData(openCardResponse.toByteString()).build().toByteString());
                        room.getSeats().stream().filter(seat1 -> SanGongTcpService.userClients.containsKey(seat1.getUserId())).forEach(seat1 -> {
                            SanGongTcpService.userClients.get(seat1.getUserId()).send(response.build(), seat1.getUserId());
                        });
                    }
                }
                if (hasNoOpen) {
                    room.gameOver(response, redisService);
                }
            }
            redisService.addCache("room" + roomNo, JSON.toJSONString(room));
            redisService.unlock("lock_room" + roomNo);
        }
    }
}
