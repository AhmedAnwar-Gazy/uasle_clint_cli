package orgs.client;

import orgs.utils.StunClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;

public class test {






    public static String getPublicIpAddress() {
        try {
            URL url = new URL("http://checkip.amazonaws.com");
            try (BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()))) {
                String publicIp = in.readLine();
                return publicIp != null ? publicIp.trim() : null;
            }
        } catch (IOException e) {
            System.err.println("Error fetching public IP address: " + e.getMessage());
            // Fallback to local IP if public IP cannot be fetched
            try {
                String localIp = InetAddress.getLocalHost().getHostAddress();
                System.out.println("Falling back to local IP: " + localIp);
                return localIp;
            } catch (UnknownHostException ex) {
                System.err.println("Error getting local host address: " + ex.getMessage());
                return null;
            }
        }
    }

    public static void main(String[] args) throws IOException {
        String myPublicIP = getPublicIpAddress();
        System.out.println("my public ip : "+ myPublicIP);

    }

}
