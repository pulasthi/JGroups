package org.jgroups.tests;

import org.jgroups.*;
import org.jgroups.protocols.FD;
import org.jgroups.protocols.FD_ALL;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.util.Util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests overlapping merges, e.g. A: {A,B}, B: {A,B} and C: {A,B,C}. Tests unicast tables<br/>
 * Related JIRA: https://jira.jboss.org/jira/browse/JGRP-940
 * @author Bela Ban
 * @version $Id: OverlappingUnicastMergeTest.java,v 1.1.2.4 2009/04/06 11:47:11 belaban Exp $
 */
public class OverlappingUnicastMergeTest extends ChannelTestBase {
    private JChannel a, b, c;
    private MyReceiver ra, rb, rc;

    protected void setUp() throws Exception {
        super.setUp();
        ra=new MyReceiver("A"); rb=new MyReceiver("B"); rc=new MyReceiver("C");
        a=createChannel(); a.setReceiver(ra);
        b=createChannel(); b.setReceiver(rb);
        c=createChannel(); c.setReceiver(rc);
        modifyConfigs(a, b, c);

        a.connect("OverlappingUnicastMergeTest");
        b.connect("OverlappingUnicastMergeTest");
        c.connect("OverlappingUnicastMergeTest");

        View view=c.getView();
        assertEquals("view is " + view, 3, view.size());
    }

    protected void tearDown() throws Exception {
        super.tearDown();
        Util.close(c,b,a);
    }


    public void testWithAllViewsInSync() throws Exception {
        sendAndCheckMessages(5, a, b, c);
    }

    /**
     * Verifies that unicasts are received correctly by all participants after an overlapping merge. The following steps
     * are executed:
     * <ol>
     * <li/>Group is {A,B,C}, disable shunning in all members. A is the coordinator
     * <li/>MERGE2 is removed from all members
     * <li/>VERIFY_SUSPECT is removed from all members
     * <li/>Everyone sends 5 unicast messages to everyone else
     * <li/>A VIEW(B,C) is injected into B and C
     * <li/>B and C install {B,C}
     * <li/>B and C trash the connection table for A in UNICAST
     * <li/>A still has view {A,B,C} and all connection tables intact in UNICAST
     * <li/>We now send N unicasts from everyone to everyone else, all the unicasts should be received.
     * </ol>
     */
    public void testWithViewBC() throws Exception {
        // Inject view {B,C} into B and C:
        View new_view=Util.createView(b.getLocalAddress(), 10, b.getLocalAddress(), c.getLocalAddress());
        injectView(new_view, b, c);
        assertEquals("A's view is " + a.getView(), 3, a.getView().size());
        assertEquals("B's view is " + b.getView(), 2, b.getView().size());
        assertEquals("C's view is " + c.getView(), 2, c.getView().size());
        sendAndCheckMessages(5, a, b, c);
    }

    public void testWithViewA() throws Exception {
        // Inject view {A} into A, B and C:
        View new_view=Util.createView(a.getLocalAddress(), 10, a.getLocalAddress());
        injectView(new_view, a, b, c);
        sendAndCheckMessages(5, a, b, c);
    }

    public void testWithViewC() throws Exception {
        // Inject view {A} into A, B and C:
        View new_view=Util.createView(c.getLocalAddress(), 10, c.getLocalAddress());
        injectView(new_view, a, b, c);
        sendAndCheckMessages(5, a, b, c);
    }

    public void testWithEveryoneHavingASingletonView() throws Exception {
        // Inject view {A} into A, B and C:
        injectView(Util.createView(a.getLocalAddress(), 10, a.getLocalAddress()), a);
        injectView(Util.createView(b.getLocalAddress(), 10, b.getLocalAddress()), b);
        injectView(Util.createView(c.getLocalAddress(), 10, c.getLocalAddress()), c);
        sendAndCheckMessages(5, a, b, c);
    }


    private static void injectView(View view, JChannel ... channels) {
        for(JChannel ch: channels) {
            ch.down(new Event(Event.VIEW_CHANGE, view));
            ch.up(new Event(Event.VIEW_CHANGE, view));
        }
        for(JChannel ch: channels) {
            MyReceiver receiver=(MyReceiver)ch.getReceiver();
            System.out.println("[" + receiver.name + "] view=" + ch.getView());
        }
    }


    private void sendAndCheckMessages(int num_msgs, JChannel ... channels) throws Exception {
        ra.clear(); rb.clear(); rc.clear();
        // 1. send unicast messages
        Set<Address> mbrs=new HashSet<Address>(channels.length);
        for(JChannel ch: channels)
            mbrs.add(ch.getLocalAddress());

        for(JChannel ch: channels) {
            Address addr=ch.getLocalAddress();
            for(Address dest: mbrs) {
                for(int i=1; i <= num_msgs; i++) {
                    ch.send(dest, null, "unicast msg #" + i + " from " + addr);
                }
            }
        }
        Util.sleep(1000);
        int total_msgs=num_msgs * channels.length;
        MyReceiver[] receivers=new MyReceiver[channels.length];
        for(int i=0; i < channels.length; i++)
            receivers[i]=(MyReceiver)channels[i].getReceiver();
        checkReceivedMessages(total_msgs, receivers);
    }

    private static void checkReceivedMessages(int num_ucasts, MyReceiver ... receivers) {
        for(MyReceiver receiver: receivers) {
            List<Message> ucasts=receiver.getUnicasts();
            int ucasts_received=ucasts.size();
            System.out.println("receiver " + receiver + ": ucasts=" + ucasts_received);
            assertEquals("ucasts for " + receiver + ": " + print(ucasts), num_ucasts, ucasts_received);
        }
    }

    public static String print(List<Message> list) {
        StringBuilder sb=new StringBuilder();
        for(Message msg: list) {
            sb.append(msg.getSrc()).append(": ").append(msg.getObject()).append(" ");
        }
        return sb.toString();
    }

    private static void modifyConfigs(JChannel ... channels) throws Exception {
        for(JChannel ch: channels) {
            ProtocolStack stack=ch.getProtocolStack();

            FD fd=(FD)stack.findProtocol(FD.class);
            if(fd != null)
                fd.setShun(false);

            FD_ALL fd_all=(FD_ALL)stack.findProtocol(FD_ALL.class);
            if(fd_all != null)
                fd_all.setShun(false);

            stack.removeProtocol("MERGE2");
            stack.removeProtocol("VERIFY_SUSPECT");
            stack.removeProtocol("FC");
        }
    }



    private static class MyReceiver extends ReceiverAdapter {
        final String name;
        final List<Message> ucasts=new ArrayList<Message>(20);

        public MyReceiver(String name) {
            this.name=name;
        }

        public void receive(Message msg) {
            Address dest=msg.getDest();
            boolean mcast=dest == null;
            if(!mcast)
                ucasts.add(msg);
        }

        public void viewAccepted(View new_view) {
            // System.out.println("[" + name + "] " + new_view);
        }

        public List<Message> getUnicasts() { return ucasts; }
        public void clear() {ucasts.clear();}

        public String toString() {
            return name;
        }
    }



}