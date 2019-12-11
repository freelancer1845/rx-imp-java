package rximp.api;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * RxImpMessage
 */
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RxImpMessage {

    public static final int STATE_NEXT = 0;
    public static final int STATE_ERROR = 1;
    public static final int STATE_COMPLETE = 2;
    public static final int STATE_SUBSCRIBE = 3;
    public static final int STATE_DISPOSE = 4;

    public String id;
    public String topic;
    public int count;
    public int rx_state;

    
    public String payload;

    public RxImpMessage(String topic, int count, int rx_state, String payload) {
        this.id = UUID.randomUUID()
                      .toString();
        this.topic = topic;
        this.count = count;
        this.rx_state = rx_state;
        this.payload = payload;
    }

}