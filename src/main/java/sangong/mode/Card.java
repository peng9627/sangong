package sangong.mode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Created by pengyi
 * Date : 16-6-12.
 */
public class Card {

    public static List<Integer> getAllCard() {
        return new ArrayList<>(Arrays.asList(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
                102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114,
                202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213, 214,
                302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 314));
    }

    public static boolean isSanGong(List<Integer> cardList) {
        return cardList.get(0) % 100 > 10 && cardList.get(0) % 100 < 14
                && cardList.get(1) % 100 > 10 && cardList.get(1) % 100 < 14
                && cardList.get(2) % 100 > 10 && cardList.get(2) % 100 < 14;
    }

    public static int getGongCardSize(List<Integer> cardList) {
        int size = 0;
        for (Integer integer : cardList) {
            if (integer % 100 > 10 && integer % 100 < 14) {
                size++;
            }
        }
        return size;
    }

    public static int getCardsValue(List<Integer> cardList) {
        int value = 0;
        for (Integer integer : cardList) {
            if (integer % 100 < 10) {
                value += integer % 100;
            }
            if (integer % 100 == 14) {
                value += 1;
            }
        }
        return value % 10;
    }

    public static boolean compare(List<Integer> cardList, List<Integer> cards) {

        //先比较公牌数
        int size = getGongCardSize(cardList);
        int otherSize = getGongCardSize(cards);

        if (size != otherSize && (size == 3 || otherSize == 3)) {
            return size > otherSize;
        }
        List<Integer> cardListArray = new ArrayList<>();
        for (Integer integer : cardList) {
            if (integer % 100 == 14) {
                cardListArray.add(integer - 13);
            } else {
                cardListArray.add(integer);
            }

        }
        cardListArray.sort(new Comparator<Integer>() {
            @Override
            public int compare(Integer o1, Integer o2) {
                if (o1 % 100 == o2 % 100) {
                    return o1 / 100 > o2 / 100 ? 1 : -1;
                } else {
                    return o1 % 100 > o2 % 100 ? 1 : -1;
                }
            }
        });

        List<Integer> cardsArray = new ArrayList<>();
        for (Integer integer : cards) {
            if (integer % 100 == 14) {
                cardsArray.add(integer - 13);
            } else {
                cardsArray.add(integer);
            }

        }
        cardsArray.sort(new Comparator<Integer>() {
            @Override
            public int compare(Integer o1, Integer o2) {
                if (o1 % 100 == o2 % 100) {
                    return o1 / 100 > o2 / 100 ? 1 : -1;
                } else {
                    return o1 % 100 > o2 % 100 ? 1 : -1;
                }
            }
        });

        if (size == 3 && otherSize == 3) {
            if (cardListArray.get(2) % 100 == cardsArray.get(2) % 100) {
                return cardListArray.get(2) > cardsArray.get(2);
            } else {
                return cardListArray.get(2) % 100 > cardsArray.get(2) % 100;
            }
        }

        //比较点子
        int cardListValue = getCardsValue(cardList);
        int cardsValue = getCardsValue(cards);

        if (cardListValue != cardsValue) {
            return cardListValue > cardsValue;
        }

        if (size != otherSize) {
            return size > otherSize;
        }

        //第二张是公牌
        if (cardListArray.get(1) % 100 > 10) {
            if (cardListArray.get(0) % 100 != cardsArray.get(0) % 100) {
                return cardListArray.get(0) % 100 > cardsArray.get(0) % 100;
            } else {
                return cardListArray.get(0) > cardsArray.get(0);
            }
        } else {
            if (cardListArray.get(1) % 100 != cardsArray.get(1) % 100) {
                return cardListArray.get(1) % 100 > cardsArray.get(1) % 100;
            } else {
                return cardListArray.get(1) > cardsArray.get(1);
            }
        }

    }
}
