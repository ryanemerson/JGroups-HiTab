package org.jgroups.protocols.RMSysIntegratedNMC;

import org.jgroups.*;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.protocols.tom.ToaHeader;
import org.jgroups.util.Util;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * // TODO: Document this
 *
 * @author ryan
 * @since 4.0
 */
public class Test extends ReceiverAdapter {
    public static void main(String[] args) throws Exception{
        String propsFile = "RMSysIntegrated.xml";
        String initiator = "";
        int numberOfMessages = 100000;
        for (int i = 0; i < args.length; i++) {
            if("-config".equals(args[i])) {
                propsFile = args[++i];
                continue;
            }
            if("-nr-messages".equals(args[i])) {
                numberOfMessages = Integer.parseInt(args[++i]);
                continue;
            }
            if("-initiator".equals(args[i])) {
                initiator = args[++i];
                continue;
            }
        }
        new Test(propsFile, numberOfMessages, initiator).run();
    }

    private final String PROPERTIES_FILE;
    private final int NUMBER_MESSAGES_TO_SEND;
    private final int NUMBER_MESSAGES_PER_FILE = 5000;
    private final String INITIATOR;
    private final String PATH = "/work/a7109534/";
    private final List<Header> deliveredMessages = new ArrayList<Header>();
    private ExecutorService outputThread = Executors.newSingleThreadExecutor();
    private JChannel channel;
    private int count = 1;
    private boolean startSending = false;
    private int completeMsgsReceived = 0;
    private int msgsReceived = 0;
    private long startTime;
    private boolean allMessagesReceived = false;
    private Future lastOutputFuture;

    public Test(String propsFile, int numberOfMessages, String initiator) {
        PROPERTIES_FILE = propsFile;
        NUMBER_MESSAGES_TO_SEND = numberOfMessages;
        INITIATOR = initiator;
    }

    public void run() throws Exception {
        ExecutorService threadPool = Executors.newFixedThreadPool(5);

        System.out.println(PROPERTIES_FILE + " | " + NUMBER_MESSAGES_TO_SEND + " messages | Initiator " + INITIATOR);
        channel = new JChannel(PROPERTIES_FILE);
        channel.setReceiver(this);
        channel.connect("uperfBox");

        ClassConfigurator.add((short) 1025, TestHeader.class); // Add Header to magic map without adding to file
        System.out.println("Channel Created | lc := " + channel.getAddress());
        System.out.println("Number of message to send := " + NUMBER_MESSAGES_TO_SEND);

        Util.sleep(1000 * 30);
        int sentMessages = 0;
        startTime = System.nanoTime();

        if (channel.getAddress().toString().contains(INITIATOR))
            sendStartMessage(channel);

        while (true) {
            if (startSending) {
                AnycastAddress anycastAddress = new AnycastAddress(channel.getView().getMembers());
                final Message message = new Message(anycastAddress, sentMessages);
//                threadPool.execute(new Runnable() {
//                    @Override
//                    public void run() {
//                        try {
//                            channel.send(message);
//                        } catch (Exception e) {
//                            System.out.println(e);
//                        }
//                    }
//                });
                channel.send(message);
                sentMessages++;

//                Util.sleep(1);

                if (sentMessages == NUMBER_MESSAGES_TO_SEND)
                    break;
            } else {
                Util.sleep(1);
            }
        }
        System.out.println("Sending finished! | Time Taken := " + TimeUnit.MILLISECONDS.convert(System.nanoTime() - startTime, TimeUnit.NANOSECONDS));

        while (!allMessagesReceived || channel.getView().size() != completeMsgsReceived || (lastOutputFuture == null || !lastOutputFuture.isDone()))
            Util.sleep(100);

        System.out.println("Test Finished");
        System.exit(0);
    }

    public void sendStartMessage(JChannel channel) throws Exception {
        sendStatusMessage(channel, new TestHeader(TestHeader.START_SENDING));
    }

    private void sendCompleteMessage(JChannel channel) throws Exception {
        sendStatusMessage(channel, new TestHeader(TestHeader.SENDING_COMPLETE));
    }

    private void sendStatusMessage(JChannel channel, TestHeader header) throws Exception {
//        Message message = new Message(new AnycastAddress(channel.getView().getMembers()));
        Message message = new Message(null);
        message.putHeader((short) 1025, header);
        message.setFlag(Message.Flag.NO_TOTAL_ORDER);
        channel.send(message);
    }

    public void receive(Message msg) {
        Header header = msg.getHeader((short)1025);
        if (header != null) {
            if (((TestHeader)header).type == TestHeader.START_SENDING && !startSending) {
                startSending = true;
                System.out.println("Start sending msgs ------");
            } else {
                completeMsgsReceived++;

                if (msg.src() != null)
                    System.out.println("Complete message received from " + msg.src());

                int numberOfNodes = channel.getView().getMembers().size();
                if (completeMsgsReceived ==  numberOfNodes) {
                    System.out.println("Cluster finished! | Time Taken := " + TimeUnit.MILLISECONDS.convert(System.nanoTime() - startTime, TimeUnit.NANOSECONDS));
                }
            }
            return;
        }

        synchronized (deliveredMessages) {
            short protocolId = (short) (PROPERTIES_FILE.equalsIgnoreCase("RMSysIntegrated.xml") ? 1008 : 58);
            header = msg.getHeader(protocolId);
            deliveredMessages.add(header);

//            System.out.println("Received msg " + ((RMCastHeader)header).getId());

            if (deliveredMessages.size() % NUMBER_MESSAGES_PER_FILE == 0) {
                final List<Header> outputHeaders = new ArrayList<Header>(deliveredMessages);
                deliveredMessages.clear();
                // Output using a single thread to ensure that this operation does not effect receiving messages
                lastOutputFuture = outputThread.submit(new Runnable() {
                    @Override
                    public void run() {
                        writeHeadersToFile(outputHeaders);
                    }
                });
            }

            System.out.println("Msgs received := " + msgsReceived + 1);
            if (++msgsReceived == (NUMBER_MESSAGES_TO_SEND * channel.getView().size())) {
                try {
                    sendCompleteMessage(channel);
                } catch (Exception e) {
                }
            }
        }
    }

    public void viewAccepted(View view) {
        System.out.println("New View := " + view);
    }

    private PrintWriter getPrintWriter() {
        try {
            new File(PATH).mkdirs();
            return new PrintWriter(new BufferedWriter(new FileWriter(PATH + "DeliveredMessages" + channel.getAddress() + "-" + count + ".csv",
                    true)), true);
        } catch (Exception e) {
            System.out.println("Error: " + e);
            return null;
        }
    }

    private void writeHeadersToFile(List<Header> headers) {
        PrintWriter out = getPrintWriter();
        for (Header header : headers)
            if (PROPERTIES_FILE.equalsIgnoreCase("RMSysIntegrated.xml"))
                out.println(((RMCastHeader) header).getId());
            else if (PROPERTIES_FILE.equalsIgnoreCase("toa.xml"))
                out.println(((ToaHeader)header).getMessageID());
        out.flush();

        int numberOfNodes = channel.getView().size();
        int totalNumberOfRounds = (numberOfNodes * NUMBER_MESSAGES_TO_SEND) / NUMBER_MESSAGES_PER_FILE;
        if (count == totalNumberOfRounds) {
            allMessagesReceived = true;
            System.out.println("&&&&&&&& Final Count := " + count + " | totalNumberOfRounds := " + totalNumberOfRounds +
                    " | numberOfMessagesToSend := " + NUMBER_MESSAGES_TO_SEND + " | NUMBER_MESSAGES_PER_FILE := " + NUMBER_MESSAGES_PER_FILE);
        }
        System.out.println("Count == " + count + " | numberOfRounds := " + totalNumberOfRounds);
        count++;
    }

    public static class TestHeader extends Header {
        public static final byte START_SENDING = 1;
        public static final byte SENDING_COMPLETE = 2;

        private byte type;

        public TestHeader() {}

        public TestHeader(byte type) {
            this.type = type;
        }

        @Override
        public int size() {
            return Global.BYTE_SIZE;
        }

        @Override
        public void writeTo(DataOutput out) throws Exception {
            out.writeByte(type);
        }

        @Override
        public void readFrom(DataInput in) throws Exception {
            type = in.readByte();
        }

        @Override
        public String toString() {
            return "TestHeader{" +
                    "type=" + type +
                    '}';
        }
    }
}