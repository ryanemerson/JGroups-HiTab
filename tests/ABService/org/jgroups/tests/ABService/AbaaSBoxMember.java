package org.jgroups.tests.ABService;

import org.jgroups.JChannel;

/**
 * Class that provides a 'dumb' application, that simply runs a network stack.  Useful for simulating ordering box members
 *
 * @author Ryan Emerson
 * @since 4.0
 */
public class AbaaSBoxMember {
    public static void main(String[] args) throws Exception {
        String properties = "";
        String channel = "";
        for (int i=0; i < args.length; i++) {
            if ("-props".equals(args[i])) {
                properties = args[++i];
                continue;
            }
            if ("-channel".equals(args[i])) {
                channel = args[++i];
            }
        }

        JChannel jChannel = new JChannel(properties);
        jChannel.connect(channel);
    }
}