package sangong.mode;

import java.util.ArrayList;
import java.util.List;

public class Record {

    private Integer banker;
    private List<OperationHistory> historyList = new ArrayList<>();
    private List<SeatRecord> seatRecordList = new ArrayList<>();//座位战绩信息

    public Integer getBanker() {
        return banker;
    }

    public void setBanker(Integer banker) {
        this.banker = banker;
    }

    public List<OperationHistory> getHistoryList() {
        return historyList;
    }

    public void setHistoryList(List<OperationHistory> historyList) {
        this.historyList = historyList;
    }

    public List<SeatRecord> getSeatRecordList() {
        return seatRecordList;
    }

    public void setSeatRecordList(List<SeatRecord> seatRecordList) {
        this.seatRecordList = seatRecordList;
    }
}
