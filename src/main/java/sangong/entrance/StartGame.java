package sangong.entrance;

import sangong.constant.Constant;

/**
 * Author pengyi
 * Date 17-7-24.
 */

public class StartGame {

    public static void main(String[] args) {
        Constant.init();
        new Thread(new SanGongTcpService()).start();
    }
}
