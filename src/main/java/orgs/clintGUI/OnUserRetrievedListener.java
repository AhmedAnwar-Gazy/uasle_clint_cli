package orgs.clintGUI;

import orgs.model.User;

/**
 * Listener for retrieving a single User object (e.g., by ID or phone number).
 */
public interface OnUserRetrievedListener {
    void onUserRetrieved(User user);
}
