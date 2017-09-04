package sangong.entrance;

import com.google.protobuf.GeneratedMessageV3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sangong.mode.GameBase;
import sangong.redis.RedisService;
import sangong.utils.ByteUtils;
import sangong.utils.CoreStringUtils;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Created by pengyi
 * Date 2017/7/25.
 */
public class MessageReceive implements Runnable {
    final OutputStream os;
    private final InputStream is;
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    private Socket s;
    private Boolean connect;
    private byte[] md5Key = "2704031cd4814eb2a82e47bd1d9042c6".getBytes();
    private SanGongClient client;

    MessageReceive(Socket s, RedisService redisService) {

        this.s = s;
        connect = true;
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            inputStream = s.getInputStream();
            outputStream = s.getOutputStream();
        } catch (EOFException e) {
            logger.info("socket.shutdown.message");
            close();
        } catch (IOException e) {
            logger.info("socket.connection.fail.message" + e.getMessage());
            close();
        }
        is = inputStream;
        os = outputStream;

        client = new SanGongClient(redisService, this);
    }

    public void send(GeneratedMessageV3 messageV3, int userId) {
        try {
            if (0 == userId) {
                String md5 = CoreStringUtils.md5(ByteUtils.addAll(md5Key, messageV3.toByteArray()), 32, false);
                messageV3.sendTo(os, md5);
                logger.info("mahjong send:len=" + messageV3);
            }
            if (SanGongTcpService.userClients.containsKey(userId)) {
                synchronized (SanGongTcpService.userClients.get(userId).os) {
                    OutputStream os = SanGongTcpService.userClients.get(userId).os;
                    String md5 = CoreStringUtils.md5(ByteUtils.addAll(md5Key, messageV3.toByteArray()), 32, false);
                    messageV3.sendTo(os, md5);
                    logger.info("mahjong send:len=\n" + messageV3 + "\nuser=" + userId + "\n");
                }
            }
        } catch (IOException e) {
            logger.info("socket.server.sendMessage.fail.message" + e.getMessage());
//            client.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void close() {
        connect = false;
        try {
            client.close();
            if (is != null)
                is.close();
            if (os != null)
                os.close();
            if (s != null) {
                s.close();
            }
        } catch (IOException e1) {
            e1.printStackTrace();
        }
    }

    private int readInt(InputStream is) throws IOException {
        int ch1 = is.read();
        int ch2 = is.read();
        int ch3 = is.read();
        int ch4 = is.read();
        if ((ch1 | ch2 | ch3 | ch4) < 0) {
            throw new EOFException();
        }
        return (ch1 << 24 | ((ch2 << 16) & 0xff) | ((ch3 << 8) & 0xff) | (ch4 & 0xFF));
    }

    private String readString(InputStream is) throws IOException {
        int len = readInt(is);
        byte[] bytes = new byte[len];
        is.read(bytes);
        return new String(bytes);
    }

    @Override
    public void run() {
        try {
            while (connect) {
                int len = readInt(is);
                String md5 = readString(is);
                len -= md5.getBytes().length + 4;
                byte[] data = new byte[len];
                boolean check = true;
                if (0 != len) {
                    int l = is.read(data);
                    check = CoreStringUtils.md5(ByteUtils.addAll(md5Key, data), 32, false).equalsIgnoreCase(md5);
                }
                if (check) {
                    client.receive(GameBase.BaseConnection.parseFrom(data));
                }
            }
        } catch (EOFException e) {
            logger.info("socket.shutdown.message");
            close();
        } catch (IOException e) {
            logger.info("socket.dirty.shutdown.message" + e.getMessage());
            e.printStackTrace();
            close();
        } catch (Exception e) {
            logger.info("socket.dirty.shutdown.message");
            e.printStackTrace();
            close();
        }
    }
}
