package orgs.clintGUI;

import orgs.model.User;

/**
 * Listener for successful login events.
 */
public interface OnLoginSuccessListener {
    void onLoginSuccess(User user);
}
