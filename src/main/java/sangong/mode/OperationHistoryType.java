package sangong.mode;

/**
 * Created by pengyi
 * Date : 16-6-12.
 */
public enum OperationHistoryType {

    PLAY_CARD("出牌", 1),;

    private String name;
    private Integer values;

    OperationHistoryType(String name, Integer values) {
        this.name = name;
        this.values = values;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getValues() {
        return values;
    }

    public void setValues(Integer values) {
        this.values = values;
    }
}
