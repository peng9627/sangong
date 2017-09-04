package sangong.mode;

import java.util.List;

/**
 * Created by pengyi
 * Date 2017/7/28.
 */
public class OperationHistory {

    private int userId;
    private OperationHistoryType historyType;
    private List<Integer> cards;

    public OperationHistory() {
    }

    public OperationHistory(int userId, OperationHistoryType historyType, List<Integer> cards) {
        this.userId = userId;
        this.historyType = historyType;
        this.cards = cards;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public OperationHistoryType getHistoryType() {
        return historyType;
    }

    public void setHistoryType(OperationHistoryType historyType) {
        this.historyType = historyType;
    }

    public List<Integer> getCards() {
        return cards;
    }

    public void setCards(List<Integer> cards) {
        this.cards = cards;
    }
}
