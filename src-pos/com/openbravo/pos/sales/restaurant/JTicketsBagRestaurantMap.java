//    uniCenta oPOS  - Touch Friendly Point Of Sale
//    Copyright (c) 2009-2014 uniCenta & previous Openbravo POS works
//    http://www.unicenta.com
//
//    This file is part of uniCenta oPOS
//
//    uniCenta oPOS is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//   uniCenta oPOS is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with uniCenta oPOS.  If not, see <http://www.gnu.org/licenses/>.

package com.openbravo.pos.sales.restaurant;

import com.openbravo.basic.BasicException;
import com.openbravo.data.gui.MessageInf;
import com.openbravo.data.gui.NullIcon;
import com.openbravo.data.loader.SentenceList;
import com.openbravo.data.loader.SerializerReadClass;
import com.openbravo.data.loader.StaticSentence;
import com.openbravo.pos.customers.CustomerInfo;
import com.openbravo.pos.forms.*;
import com.openbravo.pos.sales.*;
import com.openbravo.pos.ticket.TicketInfo;
import com.openbravo.pos.ticket.TicketLineInfo;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import javax.swing.*;

/**
 *
 * @author JG uniCenta
 */
public class JTicketsBagRestaurantMap extends JTicketsBag {

    /**
     *
     */
    public static void newticket() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    private static class ServerCurrent {

        public ServerCurrent() {
        }
    }

//    private static final Icon ICO_OCU = new ImageIcon(JTicketsBag.class.getResource("/com/openbravo/images/edit_group.png"));
//    private static final Icon ICO_FRE = new NullIcon(22, 22);
        
    private java.util.List<Place> m_aplaces;
    private java.util.List<Floor> m_afloors;
    
    private JTicketsBagRestaurant m_restaurantmap;  
    private JTicketsBagRestaurantRes m_jreservations;   
    
    private Place m_PlaceCurrent;

// TODO - Add Server JG 03.07.2011
    private ServerCurrent m_ServerCurrent;
    
    // State vars
    private Place m_PlaceClipboard;  
    private CustomerInfo customer;

    private DataLogicReceipts dlReceipts = null;
    private DataLogicSales dlSales = null;
    private final RestaurantDBUtils restDB;
    private static final Icon ICO_OCU_SM = new ImageIcon(Place.class.getResource("/com/openbravo/images/edit_group_sm.png"));
    private static final Icon ICO_WAITER = new NullIcon(1, 1);  
    private static final Icon ICO_FRE = new NullIcon(22, 22);
    private String waiterDetails;
    private String customerDetails;
    private String tableName;

    // Synchronisation avec l'API mobile : verrou de table + version de commande
    private final TableTicketStore store;
    private final TableLocks locks = TableLocks.get();
    private long m_currentVersion;          // version de la commande ouverte a l'ecran
    private byte[] m_currentOpenBytes;      // contenu a l'ouverture (pour ne pas reecrire sans changement)

    /** Creates new form JTicketsBagRestaurant
     * @param app
     * @param panelticket */

    public JTicketsBagRestaurantMap(AppView app, TicketsEditor panelticket) {
        
        super(app, panelticket);
      
        restDB = new  RestaurantDBUtils(app);        
        
        dlReceipts = (DataLogicReceipts) app.getBean("com.openbravo.pos.sales.DataLogicReceipts");
        dlSales = (DataLogicSales) m_App.getBean("com.openbravo.pos.forms.DataLogicSales");
        store = new TableTicketStore(app.getSession());

        // Une commande modifiee depuis un telephone : rafraichir le plan de salle
        // (seulement s'il est affiche, jamais pendant qu'une table est ouverte)
        locks.addListener(new TableLocks.Listener() {
            @Override
            public void tableChanged(String tableId) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        if (m_PlaceCurrent == null && isShowing()) {
                            loadTickets();
                            printState();
                        }
                    }
                });
            }
        });

        m_restaurantmap = new JTicketsBagRestaurant(app, this);
        m_PlaceCurrent = null;
        m_PlaceClipboard = null;
        customer = null;
            
        try {
            SentenceList sent = new StaticSentence(
                    app.getSession(), 
                    "SELECT ID, NAME, IMAGE FROM FLOORS ORDER BY NAME", 
                    null, 
                    new SerializerReadClass(Floor.class));
            m_afloors = sent.list();
               
                
            
        } catch (BasicException eD) {
            m_afloors = new ArrayList<>();
        }
        try {
            SentenceList sent = new StaticSentence(
                    app.getSession(), 
                    // "SELECT ID, NAME, X, Y, FLOOR, CUSTOMER FROM PLACES ORDER BY FLOOR", 
                    "SELECT ID, NAME, X, Y, FLOOR, CUSTOMER, WAITER, TICKETID, TABLEMOVED FROM PLACES ORDER BY FLOOR",
                    null, 
                    new SerializerReadClass(Place.class));
            m_aplaces = sent.list();
        } catch (BasicException eD) {
            m_aplaces = new ArrayList<>();
        } 
        

        initComponents(); 
          
        // add the Floors containers
        if (m_afloors.size() > 1) {
            // A tab container for 2 or more floors
            JTabbedPane jTabFloors = new JTabbedPane();
            jTabFloors.applyComponentOrientation(getComponentOrientation());
            jTabFloors.setBorder(new javax.swing.border.EmptyBorder(new Insets(5, 5, 5, 5)));
            jTabFloors.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            jTabFloors.setFocusable(false);
            jTabFloors.setRequestFocusEnabled(false);
            m_jPanelMap.add(jTabFloors, BorderLayout.CENTER);
            
            for (Floor f : m_afloors) {
                f.getContainer().applyComponentOrientation(getComponentOrientation());
                
                JScrollPane jScrCont = new JScrollPane();
                jScrCont.applyComponentOrientation(getComponentOrientation());
                JPanel jPanCont = new JPanel();  
                jPanCont.applyComponentOrientation(getComponentOrientation());
                
                jTabFloors.addTab(f.getName(), f.getIcon(), jScrCont);     
                jScrCont.setViewportView(jPanCont);
                jPanCont.add(f.getContainer());
            }
        } else if (m_afloors.size() == 1) {
            // Just a frame for 1 floor
            Floor f = m_afloors.get(0);
            f.getContainer().applyComponentOrientation(getComponentOrientation());
            
            JPanel jPlaces = new JPanel();
            jPlaces.applyComponentOrientation(getComponentOrientation());
            jPlaces.setLayout(new BorderLayout());
            jPlaces.setBorder(new javax.swing.border.CompoundBorder(
                    new javax.swing.border.EmptyBorder(new Insets(5, 5, 5, 5)),
                    new javax.swing.border.TitledBorder(f.getName())));
            
            JScrollPane jScrCont = new JScrollPane();
            jScrCont.applyComponentOrientation(getComponentOrientation());
            JPanel jPanCont = new JPanel();
            jPanCont.applyComponentOrientation(getComponentOrientation());
            
            // jPlaces.setLayout(new FlowLayout());           
            m_jPanelMap.add(jPlaces, BorderLayout.CENTER);
            jPlaces.add(jScrCont, BorderLayout.CENTER);
            jScrCont.setViewportView(jPanCont);            
            jPanCont.add(f.getContainer());
        }   
        
        // Add all the Table buttons.
        Floor currfloor = null;
        
        
        for (Place pl : m_aplaces) {
            int iFloor = 0;
            
            if (currfloor == null || !currfloor.getID().equals(pl.getFloor())) {
                // Look for a new floor
                do {
                    currfloor = m_afloors.get(iFloor++);
                } while (!currfloor.getID().equals(pl.getFloor()));
            }

            currfloor.getContainer().add(pl.getButton());
            pl.setButtonBounds();
            pl.getButton().addActionListener(new MyActionListener(pl));
        }
        
        // Add the reservations panel
        m_jreservations = new JTicketsBagRestaurantRes(app, this);
        add(m_jreservations, "res");
    }

    /**
     *
     */
    @Override
    public void activate() {
        
        // precondicion es que no tenemos ticket activado ni ticket en el panel

        m_PlaceClipboard = null;
        customer = null;
        loadTickets();        
        printState(); 
        
        m_panelticket.setActiveTicket(null, null); 
        m_restaurantmap.activate();
       
        showView("map"); // arrancamos en la vista de las mesas.
        
        // postcondicion es que tenemos ticket activado aqui y ticket en el panel
    }
    
    /**
     *
     * @return
     */
    @Override
    public boolean deactivate() {
        
        // precondicion es que tenemos ticket activado aqui y ticket en el panel
        
        if (viewTables()) {
        
            // borramos el clipboard
            m_PlaceClipboard = null;
            customer = null;

            // guardamos el ticket (avec controle de version) et liberation de la table
            if (m_PlaceCurrent != null) {
                saveAndReleaseCurrentPlace();
                m_PlaceCurrent = null;
            }

            // desactivamos cositas.
            printState();     
            m_panelticket.setActiveTicket(null, null); 

            return true;
        } else {
            return false;
        }
        
        // postcondicion es que no tenemos ticket activado
    }

    /**
     *
     * @return
     */
    @Override
    protected JComponent getBagComponent() {
        return m_restaurantmap;
    }

    /**
     *
     * @return
     */
    @Override
    protected JComponent getNullComponent() {
        return this;
    }

    /**
     *
     * @return
     */
    public TicketInfo getActiveTicket() {
        return m_panelticket.getActiveTicket();
    }

    /**
     *
     */
    public void moveTicket() {
        
        // guardamos el ticket
        if (m_PlaceCurrent != null) {

            saveAndReleaseCurrentPlace();

            // me guardo el ticket que quiero copiar.
            m_PlaceClipboard = m_PlaceCurrent;    
            
            customer = null;
            m_PlaceCurrent = null;
        }
        
        printState();
        m_panelticket.setActiveTicket(null, null);
    }
    
    /**
     *
     * @param c
     * @return
     */
    public boolean viewTables(CustomerInfo c) {
        // deberiamos comprobar si estamos en reservations o en tables...
        if (m_jreservations.deactivate()) {
            showView("map"); // arrancamos en la vista de las mesas.
            
            m_PlaceClipboard = null;    
            customer = c;     
            printState();
            
            return true;
        } else {
            return false;
        }        
    }
    
    /**
     *
     * @return
     */
    public boolean viewTables() {
        return viewTables(null);
    }
        
    /**
     *
     */
    public void newTicket() {
        
        // guardamos el ticket
        if (m_PlaceCurrent != null) {
            saveAndReleaseCurrentPlace();
            m_PlaceCurrent = null;
        }
        
        printState();     
        m_panelticket.setActiveTicket(null, null);     
    }
    
    /**
     *
     * @return
     */
    public String getTable(){
        String id =null;
        if (m_PlaceCurrent != null) {
            id = m_PlaceCurrent.getId();
    }
        return(id);
    }
    
    /**
     *
     * @return
     */
    public String getTableName(){
        String tableName =null;
        if (m_PlaceCurrent != null) {
            tableName = m_PlaceCurrent.getName();
    }
        return(tableName);
    }

    /**
     *
     */
    @Override
    public void deleteTicket() {
        
        if (m_PlaceCurrent != null) {

            String id = m_PlaceCurrent.getId();
            boolean deleted = false;
            try {
                store.delete(id, m_currentVersion);
                deleted = true;
            } catch (OrderConflictException c) {
                // Modifiee sur un autre poste pendant que la table etait ouverte ici :
                // on la conserve et on previent, plutot que de perdre des lignes.
                JOptionPane.showMessageDialog(this,
                        AppLocal.getIntString("message.orderkeptafterconflict", m_PlaceCurrent.getName(), c.getMessage()),
                        AppLocal.getIntString("message.orderconflicttitle"), JOptionPane.WARNING_MESSAGE);
            } catch (BasicException e) {
                new MessageInf(e).show(this);
            } finally {
                locks.releaseTill(id);
            }

            m_PlaceCurrent.setPeople(!deleted);

            m_PlaceCurrent = null;
        }
        
        printState();     
        m_panelticket.setActiveTicket(null, null); 
    }

// Added JG 03.07.2011 - TODO - Change Server Dialog here

    /**
     *
     */
            public void changeServer() {

        if (m_ServerCurrent != null) {

//          Show list of Users
//          Allow Users - CurrentUsers select
//          Compare Users
//          If newServer equal.currentUser
//              Msg NoChange
//          else
//              m_ServerCurrent.setPeople(newServer);
//              Msg Changed to NewServer
        }
    }
    
    /**
     *
     */
    public void loadTickets() {

        Set<String> atickets = new HashSet<>();
        
        try {
            java.util.List<SharedTicketInfo> l = dlReceipts.getSharedTicketList();
            for (SharedTicketInfo ticket : l) {
                atickets.add(ticket.getId());
            }
        } catch (BasicException e) {
            new MessageInf(e).show(this);
        }            
            
        for (Place table : m_aplaces) {
            table.setPeople(atickets.contains(table.getId()));
        }
    }
    
    private void printState() {
        
        if (m_PlaceClipboard == null) {
            if (customer == null) {
                // Select a table
                m_jText.setText(null);
                // Enable all tables
                for (Place place : m_aplaces) {
                    place.getButton().setEnabled(true);
// get the customer details form the database
// We have set the option show details on table.   
            if (m_App.getProperties().getProperty("table.tablecolour")== null){
             tableName="<style=font-size:9px;font-weight:bold;><font color = black>" + place.getName()+"</font></style>";  
            }else{
             tableName="<style=font-size:9px;font-weight:bold;><font color =" + m_App.getProperties().getProperty("table.tablecolour")+ ">" + place.getName()+"</font></style>";     
            }
            
            if (Boolean.valueOf(m_App.getProperties().getProperty("table.showwaiterdetails")).booleanValue()){
                if (m_App.getProperties().getProperty("table.waitercolour")== null){
                    waiterDetails = (restDB.getWaiterNameInTable(place.getName()) ==null)? "":"<style=font-size:9px;font-weight:bold;><font color = red>" 
                            + restDB.getWaiterNameInTableById(place.getId())+"</font></style><br>";
                }else{
                    waiterDetails = (restDB.getWaiterNameInTable(place.getName()) ==null)? "":"<style=font-size:9px;font-weight:bold;><font color =" 
                            + m_App.getProperties().getProperty("table.waitercolour")+ ">"  + restDB.getWaiterNameInTableById(place.getId())+"</font></style><br>";
                }
                 place.getButton().setIcon(ICO_OCU_SM);
            }
               else
            {waiterDetails = ""; }

            
            if (Boolean.valueOf(m_App.getProperties().getProperty("table.showcustomerdetails")).booleanValue()){
                    place.getButton().setIcon((Boolean.valueOf(m_App.getProperties().getProperty("table.showwaiterdetails")).booleanValue()&& (restDB.getCustomerNameInTable(place.getName()) !=null))? ICO_WAITER:ICO_OCU_SM);                              
                if (m_App.getProperties().getProperty("table.customercolour")== null){                
                    customerDetails = (restDB.getCustomerNameInTable(place.getName()) ==null)? "":"<style=font-size:9px;font-weight:bold;><font color = blue>" 
                            + restDB.getCustomerNameInTableById(place.getId())+"</font></style><br>";
                }else{
                    customerDetails = (restDB.getCustomerNameInTable(place.getName()) ==null)? "":"<style=font-size:9px;font-weight:bold;><font color =" 
                            + m_App.getProperties().getProperty("table.customercolour")+ ">" + restDB.getCustomerNameInTableById(place.getId())+"</font></style><br>";
                }}
                else
            {customerDetails=""; }

            
            if ((Boolean.valueOf(m_App.getProperties().getProperty("table.showwaiterdetails")).booleanValue()) || 
                       (Boolean.valueOf(m_App.getProperties().getProperty("table.showcustomerdetails")).booleanValue())){
                        place.getButton().setText("<html><center>" + customerDetails + waiterDetails  +tableName+"</html>" );
//  JG 29 Aug 13 Bug fix }else{;
            }else{
            if (m_App.getProperties().getProperty("table.tablecolour")== null){
             tableName="<style=font-size:10px;font-weight:bold;><font color = black>" + place.getName()+"</font></style>";  
            }else{
             tableName="<style=font-size:10px;font-weight:bold;><font color =" + m_App.getProperties().getProperty("table.tablecolour")+ ">" + place.getName()+"</font></style>";     
            }
            
                place.getButton().setText("<html><center>"+tableName+"</html>");                             
            
                }
            if (!place.hasPeople()) {
                    place.getButton().setIcon(ICO_FRE);
                }    
                }

         
                m_jbtnReservations.setEnabled(true);
            } else {
                // receive a customer
                m_jText.setText(AppLocal.getIntString("label.restaurantcustomer", new Object[] {customer.getName()}));
                // Enable all tables
                for (Place place : m_aplaces) {
                    place.getButton().setEnabled(!place.hasPeople());
                }                
                m_jbtnReservations.setEnabled(false);
            }
        } else {
            // Moving or merging the receipt to another table
            m_jText.setText(AppLocal.getIntString("label.restaurantmove", new Object[] {m_PlaceClipboard.getName()}));
            // Enable all empty tables and origin table.
            for (Place place : m_aplaces) {
                place.getButton().setEnabled(true);
            }  
            m_jbtnReservations.setEnabled(false);
        }
        
    }   
    
    // =====================================================================
    // Synchronisation avec l'API mobile
    // =====================================================================

    private String tillUserName() {
        AppUser u = m_App.getAppUserView().getUser();
        return u == null ? "caisse" : u.getName();
    }

    /**
     * Prend le verrou de la table pour la caisse. Si un telephone est en train
     * de la modifier, attend un court instant puis affiche un message.
     */
    private boolean lockForTill(Place place) {
        try {
            if (locks.acquireTill(place.getId(), tillUserName(), 3000)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        new MessageInf(MessageInf.SGN_WARNING,
                AppLocal.getIntString("message.tablebusymobile", place.getName())).show(this);
        return false;
    }

    private static byte[] serialize(TicketInfo t) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
                out.writeObject(t);
            }
            return bytes.toByteArray();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * Enregistre la commande ouverte (si elle a change) en verifiant que
     * personne ne l'a modifiee entre-temps, puis libere la table.
     */
    private void saveAndReleaseCurrentPlace() {
        Place place = m_PlaceCurrent;
        TicketInfo ticket = m_panelticket.getActiveTicket();
        try {
            if (ticket == null) {
                return;
            }
            byte[] now = serialize(ticket);
            if (now != null && m_currentOpenBytes != null && Arrays.equals(now, m_currentOpenBytes)) {
                return; // aucune modification : on ne cree pas de nouvelle version
            }
            m_currentVersion = store.update(place.getId(), ticket, m_currentVersion,
                    tillUserName(), TableLocks.Source.CAISSE.label());
        } catch (OrderConflictException c) {
            resolveSaveConflict(place, ticket, c);
        } catch (BasicException e) {
            new MessageInf(e).show(this);
        } finally {
            m_currentOpenBytes = null;
            locks.releaseTill(place.getId());
        }
    }

    /**
     * La commande a ete modifiee sur un autre poste pendant qu'elle etait
     * ouverte ici. Rien n'a ete enregistre : on demande quoi faire, le choix
     * par defaut etant de garder la version deja enregistree.
     */
    private void resolveSaveConflict(Place place, TicketInfo mine, OrderConflictException c) {
        TableTicketStore.Snapshot cur = c.getCurrent();
        String title = AppLocal.getIntString("message.orderconflicttitle");
        String text = AppLocal.getIntString("message.orderconflicttext", place.getName(), c.getMessage());
        try {
            if (cur != null) {
                Object[] options = {
                    AppLocal.getIntString("button.orderkeepsaved"),
                    AppLocal.getIntString("button.orderoverwrite")};
                int r = JOptionPane.showOptionDialog(this, text, title, JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE, null, options, options[0]);
                if (r == 1) {
                    store.update(place.getId(), mine, cur.getVersion(), tillUserName(), TableLocks.Source.CAISSE.label());
                }
            } else {
                Object[] options = {
                    AppLocal.getIntString("button.orderdiscardmine"),
                    AppLocal.getIntString("button.orderrecreate")};
                int r = JOptionPane.showOptionDialog(this, text, title, JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE, null, options, options[0]);
                if (r == 1) {
                    store.create(place.getId(), mine, tillUserName(), TableLocks.Source.CAISSE.label());
                    place.setPeople(true);
                } else {
                    place.setPeople(false);
                }
            }
        } catch (OrderConflictException again) {
            JOptionPane.showMessageDialog(this, AppLocal.getIntString("message.orderconflicttext",
                    place.getName(), again.getMessage()), title, JOptionPane.WARNING_MESSAGE);
        } catch (BasicException e) {
            new MessageInf(e).show(this);
        }
    }

    private TableTicketStore.Snapshot loadSnapshot(Place place) throws BasicException {
        return store.load(place.getId());
    }

    /** Affiche la commande d'une table que la caisse tient deja (verrou pris). */
    private void setActivePlace(Place place, TicketInfo ticket, long version) {
        m_PlaceCurrent = place;
        m_currentVersion = version;
        m_currentOpenBytes = serialize(ticket);
        m_panelticket.setActiveTicket(ticket, m_PlaceCurrent.getName());
    }

    private void showView(String view) {
        CardLayout cl = (CardLayout)(getLayout());
        cl.show(this, view);
    }

    private class MyActionListener implements ActionListener {

        private final Place m_place;

        public MyActionListener(Place place) {
            m_place = place;
        }

        @Override
        public void actionPerformed(ActionEvent evt) {
            if (m_PlaceClipboard == null) {
                if (customer == null) {
                    openTable();
                } else {
                    receiveCustomer();
                }
            } else {
                moveOrMerge();
            }
        }

        /** Ouverture d'une table depuis le plan de salle. */
        private void openTable() {
            if (!lockForTill(m_place)) {
                return;
            }
            boolean opened = false;
            try {
                TableTicketStore.Snapshot snap = loadSnapshot(m_place);
                if (snap == null && !m_place.hasPeople()) {
                    // Table libre : nouvelle commande
                    TicketInfo ticket = new TicketInfo();
                    try {
                        long v = store.create(m_place.getId(), ticket, tillUserName(), TableLocks.Source.CAISSE.label());
                        m_place.setPeople(true);
                        setActivePlace(m_place, ticket, v);
                        opened = true;
                    } catch (OrderConflictException c) {
                        // creee au meme moment ailleurs (telephone ou autre poste)
                        m_place.setPeople(true);
                        new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tablefull")).show(JTicketsBagRestaurantMap.this);
                    }
                } else if (snap == null && m_place.hasPeople()) {
                    // The table is now empty
                    new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tableempty")).show(JTicketsBagRestaurantMap.this);
                    m_place.setPeople(false); // fixed
                } else if (snap != null && !m_place.hasPeople()) {
                    // The table is now full
                    new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tablefull")).show(JTicketsBagRestaurantMap.this);
                    m_place.setPeople(true);
                } else {
                    // Table occupee : on ouvre la version la plus recente
                    setActivePlace(m_place, snap.getTicket(), snap.getVersion());
                    opened = true;
                }
            } catch (BasicException e) {
                new MessageInf(e).show(JTicketsBagRestaurantMap.this);
            } finally {
                if (!opened) {
                    locks.releaseTill(m_place.getId());
                }
            }
        }

        /** Installation d'un client (reservation) sur une table libre. */
        private void receiveCustomer() {
            if (!lockForTill(m_place)) {
                return;
            }
            boolean opened = false;
            try {
                TableTicketStore.Snapshot snap = loadSnapshot(m_place);
                if (snap == null) {
                    TicketInfo ticket = new TicketInfo();
                    try {
                        ticket.setCustomer(customer.getId() == null
                                ? null
                                : dlSales.loadCustomerExt(customer.getId()));
                    } catch (BasicException e) {
                        MessageInf msg = new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.cannotfindcustomer"), e);
                        msg.show(JTicketsBagRestaurantMap.this);
                    }
                    try {
                        long v = store.create(m_place.getId(), ticket, tillUserName(), TableLocks.Source.CAISSE.label());
                        m_place.setPeople(true);
                        m_PlaceClipboard = null;
                        customer = null;
                        setActivePlace(m_place, ticket, v);
                        opened = true;
                    } catch (OrderConflictException c) {
                        m_place.setPeople(true);
                        new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tablefull")).show(JTicketsBagRestaurantMap.this);
                    }
                } else {
                    new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tablefull")).show(JTicketsBagRestaurantMap.this);
                    m_place.setPeople(true);
                    m_place.getButton().setEnabled(false);
                }
            } catch (BasicException e) {
                new MessageInf(e).show(JTicketsBagRestaurantMap.this);
            } finally {
                if (!opened) {
                    locks.releaseTill(m_place.getId());
                }
            }
        }

        /** Deplacement (vers une table libre) ou fusion (vers une table occupee). */
        private void moveOrMerge() {
            Place source = m_PlaceClipboard;
            if (!lockForTill(source)) {
                return;
            }
            boolean sourceKept = false;   // true si la caisse garde la table source ouverte
            try {
                TableTicketStore.Snapshot clip = loadSnapshot(source);
                if (clip == null) {
                    new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tableempty")).show(JTicketsBagRestaurantMap.this);
                    source.setPeople(false);
                    m_PlaceClipboard = null;
                    customer = null;
                    printState();
                    return;
                }
                TicketInfo ticketclip = clip.getTicket();

                if (source == m_place) {
                    // the same button. Canceling.
                    m_PlaceClipboard = null;
                    customer = null;
                    printState();
                    setActivePlace(source, ticketclip, clip.getVersion());
                    sourceKept = true;
                    return;
                }

                if (!lockForTill(m_place)) {
                    return;
                }
                boolean targetOpened = false;
                try {
                    TableTicketStore.Snapshot target = loadSnapshot(m_place);
                    if (!m_place.hasPeople()) {
                        // Moving the receipt to an empty table
                        if (target == null) {
                            long v;
                            try {
                                v = store.create(m_place.getId(), ticketclip, tillUserName(), TableLocks.Source.CAISSE.label());
                            } catch (OrderConflictException c) {
                                m_place.setPeople(true);
                                new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tablefull")).show(JTicketsBagRestaurantMap.this);
                                return;
                            }
                            m_place.setPeople(true);
                            deleteMovedSource(source, clip.getVersion());

                            m_PlaceClipboard = null;
                            customer = null;
                            printState();
                            setActivePlace(m_place, ticketclip, v);
                            targetOpened = true;
                        } else {
                            // Full table
                            new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tablefull")).show(JTicketsBagRestaurantMap.this);
                            m_place.setPeople(true);
                            printState();
                        }
                    } else {
                        // Merge the lines with the receipt of the table
                        if (target == null) {
                            new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tableempty")).show(JTicketsBagRestaurantMap.this);
                            m_place.setPeople(false); // fixed
                        } else if (JOptionPane.showConfirmDialog(JTicketsBagRestaurantMap.this, AppLocal.getIntString("message.mergetablequestion"), AppLocal.getIntString("message.mergetable"), JOptionPane.YES_NO_OPTION)
                                    == JOptionPane.YES_OPTION) {
                            TicketInfo ticket = target.getTicket();
                            if (ticket.getCustomer() == null) {
                                ticket.setCustomer(ticketclip.getCustomer());
                            }
                            for (TicketLineInfo line : ticketclip.getLines()) {
                                ticket.addLine(line);
                            }
                            long v;
                            try {
                                v = store.update(m_place.getId(), ticket, target.getVersion(), tillUserName(), TableLocks.Source.CAISSE.label());
                            } catch (OrderConflictException c) {
                                JOptionPane.showMessageDialog(JTicketsBagRestaurantMap.this,
                                        AppLocal.getIntString("message.orderconflicttext", m_place.getName(), c.getMessage()),
                                        AppLocal.getIntString("message.orderconflicttitle"), JOptionPane.WARNING_MESSAGE);
                                return;
                            }
                            deleteMovedSource(source, clip.getVersion());

                            m_PlaceClipboard = null;
                            customer = null;
                            //clear the original table data
                            restDB.clearCustomerNameInTable(restDB.getTableDetails(ticketclip.getId()));
                            restDB.clearWaiterNameInTable(restDB.getTableDetails(ticketclip.getId()));
                            restDB.clearTableMovedFlag(restDB.getTableDetails(ticketclip.getId()));
                            restDB.clearTicketIdInTable(restDB.getTableDetails(ticketclip.getId()));
                            printState();

                            setActivePlace(m_place, ticket, v);
                            targetOpened = true;
                        } else {
                            // Cancel merge operations
                            m_PlaceClipboard = null;
                            customer = null;
                            printState();
                            setActivePlace(source, ticketclip, clip.getVersion());
                            sourceKept = true;
                        }
                    }
                } finally {
                    if (!targetOpened) {
                        locks.releaseTill(m_place.getId());
                    }
                }
            } catch (BasicException e) {
                new MessageInf(e).show(JTicketsBagRestaurantMap.this);
            } finally {
                if (!sourceKept) {
                    locks.releaseTill(source.getId());
                }
            }
        }

        /** Supprime la commande de la table d'origine apres un deplacement / une fusion. */
        private void deleteMovedSource(Place source, long version) throws BasicException {
            try {
                store.delete(source.getId(), version);
                source.setPeople(false);
            } catch (OrderConflictException c) {
                // modifiee sur un autre poste entre-temps : on la garde et on previent
                source.setPeople(true);
                JOptionPane.showMessageDialog(JTicketsBagRestaurantMap.this,
                        AppLocal.getIntString("message.orderkeptafterconflict", source.getName(), c.getMessage()),
                        AppLocal.getIntString("message.orderconflicttitle"), JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    /**
     *
     * @param btnText
     */
    public void setButtonTextBags(String btnText){
      m_PlaceClipboard.setButtonText(btnText);
  }  
  
  

  
    
    /** This method is called from within the constructor to
     * initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is
     * always regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        m_jPanelMap = new javax.swing.JPanel();
        jPanel1 = new javax.swing.JPanel();
        jPanel2 = new javax.swing.JPanel();
        m_jbtnReservations = new javax.swing.JButton();
        m_jbtnRefresh = new javax.swing.JButton();
        m_jText = new javax.swing.JLabel();

        setLayout(new java.awt.CardLayout());

        m_jPanelMap.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        m_jPanelMap.setLayout(new java.awt.BorderLayout());

        jPanel1.setLayout(new java.awt.BorderLayout());

        jPanel2.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));

        m_jbtnReservations.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        m_jbtnReservations.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/date.png"))); // NOI18N
        m_jbtnReservations.setText(AppLocal.getIntString("button.reservations")); // NOI18N
        m_jbtnReservations.setToolTipText("Open Reservations screen");
        m_jbtnReservations.setFocusPainted(false);
        m_jbtnReservations.setFocusable(false);
        m_jbtnReservations.setMargin(new java.awt.Insets(8, 14, 8, 14));
        m_jbtnReservations.setRequestFocusEnabled(false);
        m_jbtnReservations.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jbtnReservationsActionPerformed(evt);
            }
        });
        jPanel2.add(m_jbtnReservations);

        m_jbtnRefresh.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        m_jbtnRefresh.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/openbravo/images/reload.png"))); // NOI18N
        m_jbtnRefresh.setText(AppLocal.getIntString("button.reloadticket")); // NOI18N
        m_jbtnRefresh.setToolTipText("Reload table information");
        m_jbtnRefresh.setFocusPainted(false);
        m_jbtnRefresh.setFocusable(false);
        m_jbtnRefresh.setMargin(new java.awt.Insets(8, 14, 8, 14));
        m_jbtnRefresh.setRequestFocusEnabled(false);
        m_jbtnRefresh.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jbtnRefreshActionPerformed(evt);
            }
        });
        jPanel2.add(m_jbtnRefresh);
        jPanel2.add(m_jText);

        jPanel1.add(jPanel2, java.awt.BorderLayout.LINE_START);

        m_jPanelMap.add(jPanel1, java.awt.BorderLayout.NORTH);

        add(m_jPanelMap, "map");
    }// </editor-fold>//GEN-END:initComponents

    private void m_jbtnRefreshActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jbtnRefreshActionPerformed

        m_PlaceClipboard = null;
        customer = null;
        loadTickets();     
        printState();   
        
    }//GEN-LAST:event_m_jbtnRefreshActionPerformed

    private void m_jbtnReservationsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jbtnReservationsActionPerformed

        showView("res");
        m_jreservations.activate();
        
    }//GEN-LAST:event_m_jbtnReservationsActionPerformed
    
    
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel m_jPanelMap;
    private javax.swing.JLabel m_jText;
    private javax.swing.JButton m_jbtnRefresh;
    private javax.swing.JButton m_jbtnReservations;
    // End of variables declaration//GEN-END:variables
    
}
