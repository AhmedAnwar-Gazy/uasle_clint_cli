package orgs.clintGUI;

import orgs.model.Notification;

import java.util.List;

/**
 * Listener for retrieving a list of user notifications.
 */
public interface OnNotificationsRetrievedListener {
    void onNotificationsRetrieved(List<Notification> notifications);
}
