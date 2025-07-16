package orgs.clintGUI;

import orgs.protocol.Response;

/**
 * Listener for general command responses from the server.
 */
public interface OnCommandResponseListener {
    void onCommandResponse(Response response);
}
