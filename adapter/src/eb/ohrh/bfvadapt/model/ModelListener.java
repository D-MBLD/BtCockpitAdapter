package eb.ohrh.bfvadapt.model;

import java.util.Observable;
import java.util.Observer;

public interface ModelListener extends Observer {

    @Override
    public void update(Observable observable, Object data);
}
