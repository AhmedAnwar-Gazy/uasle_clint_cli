package orgs.clintGUI;

import orgs.model.User;

import java.util.List;

/**
 * Listener for retrieving a list of all users.
 */
public interface OnAllUsersRetrievedListener {
    void onAllUsersRetrieved(List<User> users);
}
