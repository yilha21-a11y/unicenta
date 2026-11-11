//    uniCenta oPOS  - Touch Friendly Point Of Sale
//    Copyright (c) 2009-2018 uniCenta & previous Openbravo POS works
//    https://unicenta.com
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

package com.unicenta.pos.sales.restaurant;

import com.unicenta.basic.BasicException;
import com.unicenta.data.gui.MessageInf;
import com.unicenta.data.gui.NullIcon;
import com.unicenta.data.loader.SentenceList;
import com.unicenta.data.loader.SerializerReadClass;
import com.unicenta.data.loader.StaticSentence;
import com.unicenta.pos.customers.CustomerInfo;
import com.unicenta.pos.forms.*;
import com.unicenta.pos.sales.DataLogicReceipts;
import com.unicenta.pos.sales.JTicketsBag;
import com.unicenta.pos.sales.SharedTicketInfo;
import com.unicenta.pos.sales.TicketsEditor;
import com.unicenta.pos.ticket.TicketInfo;
import com.unicenta.pos.ticket.TicketLineInfo;
import com.unicenta.pos.api.ApiServer;
import com.unicenta.pos.api.KitchenService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 *
 * @author JG uniCenta
 */
@Slf4j
public class JTicketsBagRestaurantMap extends JTicketsBag {

    private static class ServerCurrent {

        public ServerCurrent() {
        }
    }

    private java.util.List<Place> m_aplaces;
    private java.util.List<Floor> m_afloors;
    
    private JTicketsBagRestaurant m_restaurantmap;
        
    private final JTicketsBagRestaurantRes m_jreservations;   
    private Place m_PlaceCurrent;
    private ServerCurrent m_ServerCurrent;
    private Place m_PlaceClipboard;  
    private CustomerInfo customer;

    private DataLogicReceipts dlReceipts = null;
    private DataLogicSales dlSales = null;
    private DataLogicSystem dlSystem = null;    
    private final RestaurantDBUtils restDB;
    private static final Icon ICO_OCU_SM = new ImageIcon(Place.class.getResource("/com/unicenta/images/edit_group_sm.png"));
    private static final Icon ICO_WAITER = new NullIcon(1, 1);  
    private static final Icon ICO_FRE = new NullIcon(22, 22);
    private String waiterDetails;
    private String customerDetails;
    private String tableName;
    private Boolean transBtns;
    private Boolean actionEnabled = true;    
    private int newX;
    private int newY;    
    private AppView m_app;
    private Boolean showLayout = false;

    // OLPOS : synchronisation avec les telephones et les autres postes
    // (verrou de table partage, identifiant + version de la commande ouverte)
    private final TableTicketStore store;
    private final TableLocks locks = TableLocks.get();
    private String m_currentOrderId;        // identifiant de la commande ouverte a l'ecran
    private long m_currentVersion;          // sa version lue a l'ouverture / apres enregistrement
    private byte[] m_currentOpenBytes;      // contenu a l'ouverture (pour ne pas reecrire sans changement)
    
        
    /** Creates new form JTicketsBagRestaurant
     * @param app
     * @param panelticket */

    public JTicketsBagRestaurantMap(AppView app, TicketsEditor panelticket) {
        
        super(app, panelticket);
      
        restDB = new  RestaurantDBUtils(app);        
        transBtns = AppConfig.getInstance().getBoolean("table.transbtn");
        
        dlReceipts = (DataLogicReceipts) app.getBean("com.unicenta.pos.sales.DataLogicReceipts");
        dlSales = (DataLogicSales) m_App.getBean("com.unicenta.pos.forms.DataLogicSales");
        dlSystem = (DataLogicSystem) m_App.getBean("com.unicenta.pos.forms.DataLogicSystem");
        store = new TableTicketStore(app.getSession());

        // OLPOS : une commande modifiee depuis un telephone rafraichit le plan de
        // salle (seulement s'il est affiche, jamais pendant qu'une table est ouverte)
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
                "SELECT ID, NAME, IMAGE FROM floors ORDER BY NAME", 
                null, 
                new SerializerReadClass(Floor.class));
            m_afloors = sent.list();
               
        } catch (BasicException eD) {
            m_afloors = new ArrayList<>();
        }
        try {
            SentenceList sent = new StaticSentence(
                app.getSession(), 
                "SELECT ID, NAME, SEATS, X, Y, FLOOR, CUSTOMER, WAITER, " 
                    + "TICKETID, TABLEMOVED, WIDTH, HEIGHT, GUESTS, OCCUPIED "
                    + "FROM places "
                    + "ORDER BY FLOOR ",
                null, 
                new SerializerReadClass(Place.class));
            m_aplaces = sent.list();
        } catch (BasicException eD) {
            m_aplaces = new ArrayList<>();
        } 
        
        initComponents(); 
        
        m_jbtnSave.setVisible(false);
        
        if (m_afloors.size() > 1) {
            JTabbedPane jTabFloors = new JTabbedPane();
            jTabFloors.applyComponentOrientation(getComponentOrientation());
            jTabFloors.setBorder(new javax.swing.border.EmptyBorder(new Insets(5, 5, 5, 5)));
            jTabFloors.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            jTabFloors.setFocusable(false);
            jTabFloors.setRequestFocusEnabled(false);
            m_jPanelMap.add(jTabFloors, BorderLayout.CENTER);
            
            m_afloors.stream().map((f) -> {
                f.getContainer().applyComponentOrientation(getComponentOrientation());
                return f;                
            }).forEach((f) -> {
                JScrollPane jScrCont = new JScrollPane();
                jScrCont.applyComponentOrientation(getComponentOrientation());
                JPanel jPanCont = new JPanel();  
                jPanCont.applyComponentOrientation(getComponentOrientation());
                
                jTabFloors.addTab(f.getName(), f.getIcon(), jScrCont);     
                jScrCont.setViewportView(jPanCont);
                jPanCont.add(f.getContainer());
            });
        } else if (m_afloors.size() == 1) {
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
            
            m_jPanelMap.add(jPlaces, BorderLayout.CENTER);
            jPlaces.add(jScrCont, BorderLayout.CENTER);
            jScrCont.setViewportView(jPanCont);            
            jPanCont.add(f.getContainer());
        }   
        
        Floor currfloor = null;
        
        for (Place pl : m_aplaces) {
            int iFloor = 0;
            
            if (currfloor == null || !currfloor.getID().equals(pl.getFloor())) {
                do {
                    currfloor = m_afloors.get(iFloor++);
                } while (!currfloor.getID().equals(pl.getFloor()));
            }

            currfloor.getContainer().add(pl.getButton());
            pl.setButtonBounds();

            if (transBtns) {
                pl.getButton().setOpaque(false);
                pl.getButton().setContentAreaFilled(false);
                pl.getButton().setBorderPainted(false);
            }
            
            pl.getButton().addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent E) {
                if (!actionEnabled) {
                    if (pl.getDiffX() == 0) {
                        pl.setDiffX(pl.getButton().getX() - pl.getX());
                        pl.setDiffY(pl.getButton().getY() - pl.getY());
                    }
                    newX = E.getX() + pl.getButton().getX();
                    newY = E.getY() + pl.getButton().getY();
                    pl.getButton().setBounds(newX + pl.getDiffX(), newY + pl.getDiffY(),
                        pl.getButton().getWidth(), pl.getButton().getHeight());
                        pl.setX(newX);
                        pl.setY(newY);
                    }
                }
            }
            );

            pl.getButton().addActionListener(new MyActionListener(pl));
        }
        
        m_jreservations = new JTicketsBagRestaurantRes(app, this);
        add(m_jreservations, "res");

        showLayout = m_App.getAppUserView().getUser().hasPermission("sales.Layout");
        if (showLayout) {
            m_jbtnLayout.setVisible(true);
            m_jbtnSave.setVisible(false);            
        } else {
            m_jbtnLayout.setVisible(false);
            m_jbtnSave.setVisible(false);            
        }        
        
        if (m_App.getProperties().getProperty("till.autoRefreshTableMap").equals("true")) {
            webLblautoRefresh.setText(java.util.ResourceBundle.getBundle("pos_messages")
                .getString("label.autoRefreshTableMapTimerON"));        
            
            Timer autoRefreshTimer = new Timer(Integer.parseInt(m_App.getProperties()
                .getProperty("till.autoRefreshTimer"))*1000, new tableMapRefresh());
    
            autoRefreshTimer.start();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                autoRefreshTimer.stop();
            } 
        } else {
            webLblautoRefresh.setText(java.util.ResourceBundle.getBundle("pos_messages")
                .getString("label.autoRefreshTableMapTimerOFF"));        
        }

}

    class tableMapRefresh implements ActionListener {
       
        @Override
        public void actionPerformed(ActionEvent e) {
            loadTickets();
            printState(); 
        }
    }   
        
    /**
     *
     */
    @Override
    public void activate() {

        showLayout = m_App.getAppUserView().getUser().hasPermission("sales.Layout");
        if (showLayout) {
            m_jbtnLayout.setVisible(true);
            m_jbtnSave.setVisible(false);
        } else {
            m_jbtnLayout.setVisible(false);
            m_jbtnSave.setVisible(false);            
        } 
        
        m_PlaceClipboard = null;
        customer = null;
        loadTickets();        
        printState(); 
        
        m_panelticket.setActiveTicket(null, null); 
        m_restaurantmap.activate();
       
        showView("map");      
    }
    
    /**
     *
     * @return
     */
    @Override
    public boolean deactivate() {
        
        if (viewTables()) {
            m_PlaceClipboard = null;
            customer = null;

            if (m_PlaceCurrent != null) {
                // OLPOS : enregistrement avec controle de version, puis liberation de la table
                saveAndReleaseCurrentPlace();
                unlockInDatabase(m_PlaceCurrent);
                m_PlaceCurrent = null;
            }
            printState();     
            m_panelticket.setActiveTicket(null, null); 

            return true;
        } else {
            return false;
        }
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
        if (m_PlaceCurrent != null) {
            saveAndReleaseCurrentPlace();   // OLPOS : controle de version
                        
            m_PlaceClipboard = m_PlaceCurrent;                                  // put FROM table to TO table
            
            customer = null;
//            m_PlaceCurrent = null;                                            // Hang on we'll clear later after we're done
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
        if (m_jreservations.deactivate()) {
            showView("map");
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

        if (m_PlaceCurrent != null) {

            try {
                String m_lockState = null;
                m_lockState = dlReceipts.getLockState(m_PlaceCurrent.getId(), m_lockState);
                dlReceipts.getSharedTicket(m_PlaceCurrent.getId());

                if ("override".equals(m_lockState)
                        || "locked".equals(m_lockState)) {
                    saveAndReleaseCurrentPlace();   // OLPOS : controle de version
                    dlReceipts.unlockSharedTicket(m_PlaceCurrent.getId(), null);
                    m_PlaceCurrent = null;

                } else {
                    JOptionPane.showMessageDialog(null
                        , AppLocal.getIntString("message.sharedticketlockoverriden")
                        , AppLocal.getIntString("title.editor")
                        , JOptionPane.INFORMATION_MESSAGE);                        
                }
            } catch (BasicException ex) {
                log.error(ex.getMessage());
            }
        }
        
        printState();     
        m_panelticket.setActiveTicket(null, null);     
    }
    
    /**
     *
     * @return
     */
    public String getTable() {
        String id = null;
        if (m_PlaceCurrent != null) {
            id = m_PlaceCurrent.getId();
    }
        return(id);
    }
    
    /**
     *
     * @return
     */
    public String getTableName() {
        String stableName = null;
        if (m_PlaceCurrent != null) {
            stableName = m_PlaceCurrent.getName();
    }
        return(stableName);
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
                // OLPOS : seulement la commande ouverte ici (identifiant + version)
                store.delete(id, m_currentOrderId, m_currentVersion);
                deleted = true;
                dlSystem.execTicketRemoved(
                new Object[] {
                    m_App.getAppUserView().getUser().getName(),
                    "Void",
                    "Ticket Deleted",
                    0.0
                });

            } catch (OrderConflictException c) {
                // Modifiee ou remplacee sur un autre poste : on la conserve et on previent,
                // plutot que de perdre des lignes.
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
            clearCurrentOrder();
        }      

        printState();     
        m_panelticket.setActiveTicket(null, null); 
    }

    /**
     *
     */
    public void changeServer() {

        if (m_ServerCurrent != null) {
        }
    }
    
    /**
     *
     */
    public void loadTickets() {
       
        Set<String> atickets = new HashSet<>();
        
        try {
            java.util.List<SharedTicketInfo> l = dlReceipts.getSharedTicketList();
            l.stream().forEach((ticket) -> {
                atickets.add(ticket.getId());
            });
        } catch (BasicException e) {
            new MessageInf(e).show(this);
        }            
            
        m_aplaces.stream().forEach((table) -> {
            table.setPeople(atickets.contains(table.getId()));
        });
    }
    
/*
 *  Populate the floor plans and tables    
*/
    private void printState() {
        String sDB;
        sDB = m_App.getProperties().getProperty("db.engine"); 
        
        if (m_PlaceClipboard == null) {
            if (customer == null) {
                m_jText.setText(null);

                m_aplaces.stream()
                        .map((place) -> {
                            place.getButton().setEnabled(true);
                            Integer guests = restDB.getGuestsInTable(place.getId());
                            place.setGuests(guests);
                            Date occupied = restDB.getOccupied(place.getId());
                            place.setOccupied(occupied);
                            return place;
                        })

                        .map((place) -> {
                            if (m_App.getProperties().getProperty("table.tablecolour")== null){
                                tableName="<style=font-size:9px;font-weight:bold;><font color = black>"
                                + place.getName()+"</font></style>";  
                            }else{
                                if (place.getOccupied() != null) {
                                    Date date = new java.util.Date();
                                    Timestamp t1 = new Timestamp(date.getTime());
                                    Timestamp t2 = new Timestamp(place.getOccupied().getTime());    
 
                                    long milliseconds = t1.getTime() - t2.getTime();
                                    int seconds = (int) milliseconds / 1000;
                                    int hours = seconds / 3600;
                                    int minutes = (seconds % 3600) / 60;

Integer count = restDB.getGuestsInTable(place.getId());
        
                                    tableName="<style=font-size:9px;font-weight:bold;><font color ="
                                        + m_App.getProperties().getProperty("table.tablecolour")+ ">"
                                        + place.getName() + " / " + place.getSeats() + "<br>"
//                                        + AppLocal.getIntString("button.guest") + place.getGuests() + "<br>"
                                        + AppLocal.getIntString("button.guest") + count + "<br>"                                            
                                        + AppLocal.getIntString("button.occupied") 
                                            + hours + "h" + ":" + minutes + "m" 
                                            + "<br>"
                                        + "</font></style>";      
                                } else {
                                    tableName="<style=font-size:9px;font-weight:bold;><font color ="
                                    + m_App.getProperties().getProperty("table.tablecolour")+ ">"
                                    + place.getName() + " / " + place.getSeats() + "<br>"
                                    + "</font></style>";
                                }
                            }
                            return place;            
                        })

                        .map((place) -> {
                            if (Boolean.parseBoolean(m_App.getProperties().getProperty("table.showwaiterdetails"))){
                                if (m_App.getProperties().getProperty("table.waitercolour")== null){
                                    waiterDetails = (restDB.getWaiterNameInTable(place.getName()) ==null)? ""
                                    :"<style=font-size:9px;font-weight:bold;><font color = red>"
                                    + restDB.getWaiterNameInTableById(place.getId())+"</font></style><br>";
                                }else{
                                    waiterDetails = (restDB.getWaiterNameInTable(place.getName()) ==null)? ""
                                    :"<style=font-size:9px;font-weight:bold;><font color ="
                                    + m_App.getProperties().getProperty("table.waitercolour")+ ">"
                                    + restDB.getWaiterNameInTableById(place.getId())+"</font></style><br>";
                                }
                                place.getButton().setIcon(ICO_OCU_SM);
                            } else {
                                waiterDetails = ""; 
                            }
                            return place;           
                        })
                        
                        .map((place) -> {
                            if (Boolean.parseBoolean(
                                    m_App.getProperties().getProperty("table.showcustomerdetails"))){
                                place.getButton().setIcon((Boolean.parseBoolean(
                                        m_App.getProperties().getProperty("table.showwaiterdetails"))
                                        && (restDB.getCustomerNameInTable(place.getName()) !=null))
                                        ? ICO_WAITER:ICO_OCU_SM);
                                if (m_App.getProperties().getProperty("table.customercolour")== null){                
                                    customerDetails = (restDB.getCustomerNameInTable(place.getName()) ==null)? ""
                                    :"<style=font-size:9px;font-weight:bold;><font color = blue>"
                                    + restDB.getCustomerNameInTableById(place.getId())+"</font></style><br>";
                                }else{
                                    customerDetails = (restDB.getCustomerNameInTable(place.getName()) ==null)? ""
                                            :"<style=font-size:9px;font-weight:bold;><font color ="
                                        + m_App.getProperties().getProperty("table.customercolour")+ ">"
                                        + restDB.getCustomerNameInTableById(place.getId())+"</font></style><br>";
                                }
                            } else {
                                customerDetails = ""; 
                            }
                            return place;
                        })
                        
                        .map((place) -> { 
                            if ((Boolean.parseBoolean(
                                    m_App.getProperties().getProperty("table.showwaiterdetails")))
                                    || (Boolean.parseBoolean(
                                            m_App.getProperties().getProperty("table.showcustomerdetails")))) {
                                place.getButton().setText("<html><center>"
                                + customerDetails + waiterDetails  +tableName+"</html>" );
                            }else{
                                if (m_App.getProperties().getProperty("table.tablecolour")== null){
                                    tableName="<style=font-size:10px;font-weight:bold;><font color = black>"
                                    + place.getName()+"</font></style>";  
                                }else{
                                    tableName="<style=font-size:10px;font-weight:bold;><font color ="
                                    + m_App.getProperties().getProperty("table.tablecolour")+ ">"
                                    + place.getName()+"</font></style>";     
                                }
                                place.getButton().setText("<html><center>"+tableName+"</html>");
                            }
                            return place;
                        })
                        
                        .filter((place) -> (!place.hasPeople()))

                        .forEach((place) -> {
                            place.getButton().setIcon(ICO_FRE);
                        });

                        m_jbtnReservations.setEnabled(true);
// places here

            } else {
                m_jText.setText(AppLocal.getIntString("label.restaurantcustomer"
                        , new Object[] {
                            customer.getName()
                        }
                )
                );

                m_aplaces.stream()
                        .forEach((place) -> {
                            place.getButton().setEnabled(!place.hasPeople());
                        });
                m_jbtnReservations.setEnabled(false);
            }
        } else {
            m_jText.setText(AppLocal.getIntString("label.restaurantmove"
                    , new Object[] {
                    m_PlaceClipboard.getName()
                }
            ));

            m_aplaces.stream()
                .forEach((place) -> {
                    Integer guests = restDB.getGuestsInTable(place.getId());
                    place.setGuests(guests);
                    Date occupied = restDB.getOccupied(place.getId());
                    place.setOccupied(occupied);                
                    place.getButton().setEnabled(true);
                });  

            m_jbtnReservations.setEnabled(false);
        }       
    }

    // =====================================================================
    // OLPOS : synchronisation avec les telephones et les autres postes
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

    private void clearCurrentOrder() {
        m_currentOrderId = null;
        m_currentVersion = 0;
        m_currentOpenBytes = null;
    }

    private void unlockInDatabase(Place place) {
        try {
            dlReceipts.unlockSharedTicket(place.getId(), null);
        } catch (BasicException e) {
            log.error(e.getMessage());
        }
    }

    /**
     * Enregistre la commande ouverte (si elle a change) en verifiant que
     * personne ne l'a modifiee ni remplacee entre-temps. Garde la table.
     * @return false si un conflit a ete detecte (l'utilisateur a choisi)
     */
    private boolean saveCurrentPlace() {
        Place place = m_PlaceCurrent;
        TicketInfo ticket = m_panelticket.getActiveTicket();
        if (place == null || ticket == null) {
            return true;
        }
        byte[] now = serialize(ticket);
        if (now != null && m_currentOpenBytes != null && Arrays.equals(now, m_currentOpenBytes)) {
            return true; // aucune modification : on ne cree pas de nouvelle version
        }
        try {
            m_currentVersion = store.update(place.getId(), ticket, m_currentOrderId, m_currentVersion,
                    tillUserName(), TableLocks.Source.CAISSE.label());
            m_currentOpenBytes = serialize(ticket);
            return true;
        } catch (OrderConflictException c) {
            resolveSaveConflict(place, ticket, c);
            return false;
        } catch (BasicException e) {
            new MessageInf(e).show(this);
            return false;
        }
    }

    /** Enregistre la commande ouverte (controle de version) puis libere la table. */
    private void saveAndReleaseCurrentPlace() {
        Place place = m_PlaceCurrent;
        try {
            saveCurrentPlace();
        } finally {
            if (place != null) {
                locks.releaseTill(place.getId());
            }
            clearCurrentOrder();
        }
    }

    /**
     * La commande a ete modifiee (ou remplacee) sur un autre poste pendant
     * qu'elle etait ouverte ici. Rien n'a ete enregistre : on demande quoi
     * faire, le choix par defaut etant de garder la version deja enregistree.
     */
    private void resolveSaveConflict(Place place, TicketInfo mine, OrderConflictException c) {
        TableTicketStore.Snapshot cur = c.getCurrent();
        String title = AppLocal.getIntString("message.orderconflicttitle");
        String text = AppLocal.getIntString("message.orderconflicttext", place.getName(), c.getMessage());
        try {
            if (cur != null && cur.getOrderId().equals(mine.getId())) {
                // meme commande, modifiee ailleurs
                Object[] options = {
                    AppLocal.getIntString("button.orderkeepsaved"),
                    AppLocal.getIntString("button.orderoverwrite")};
                int r = JOptionPane.showOptionDialog(this, text, title, JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE, null, options, options[0]);
                if (r == 1) {
                    m_currentVersion = store.update(place.getId(), mine, cur.getOrderId(), cur.getVersion(),
                            tillUserName(), TableLocks.Source.CAISSE.label());
                }
            } else if (cur == null) {
                // la commande n'existe plus (encaissee ou annulee ailleurs)
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
            } else {
                // une AUTRE commande occupe la table : on ne l'ecrase jamais
                JOptionPane.showMessageDialog(this, text, title, JOptionPane.WARNING_MESSAGE);
                place.setPeople(true);
            }
        } catch (OrderConflictException again) {
            JOptionPane.showMessageDialog(this, AppLocal.getIntString("message.orderconflicttext",
                    place.getName(), again.getMessage()), title, JOptionPane.WARNING_MESSAGE);
        } catch (BasicException e) {
            new MessageInf(e).show(this);
        }
    }

    /**
     * Avant l'encaissement : la commande enregistree doit etre exactement celle
     * ouverte ici (meme identifiant, meme version). Sinon RIEN n'est encaisse.
     */
    @Override
    public boolean checkBeforePayment() {
        if (m_PlaceCurrent == null || m_currentOrderId == null) {
            return true;
        }
        try {
            store.check(m_PlaceCurrent.getId(), m_currentOrderId, m_currentVersion);
            return true;
        } catch (OrderConflictException c) {
            String who = c.getCurrent() == null ? "" : c.getCurrent().describeLastChange();
            JOptionPane.showMessageDialog(this,
                    AppLocal.getIntString("message.orderchangedbeforepayment", m_PlaceCurrent.getName(),
                            who.isEmpty() ? "(" + c.getMessage() + ")" : who),
                    AppLocal.getIntString("message.orderconflicttitle"), JOptionPane.WARNING_MESSAGE);
            return false;
        } catch (BasicException e) {
            new MessageInf(e).show(this);
            return false;
        }
    }

    /** Utilisateur de la caisse vu par le service cuisine. */
    private KitchenService.Actor tillActor() {
        final AppUser u = m_App.getAppUserView().getUser();
        return new KitchenService.Actor() {
            @Override
            public String getUserId() {
                return u.getId();
            }

            @Override
            public String getUserName() {
                return u.getName();
            }

            @Override
            public boolean hasPermission(String permission) {
                return u.hasPermission(permission);
            }

            @Override
            public TableLocks.Source getSource() {
                return TableLocks.Source.CAISSE;
            }
        };
    }

    /**
     * Bouton Cuisine de la caisse : MEME service que les telephones. La
     * commande est d'abord enregistree (controle de version), puis envoyee en
     * arriere-plan ; la caisse affiche ensuite la commande a jour et le resultat.
     */
    @Override
    public boolean sendToKitchen() {
        final KitchenService ks = KitchenService.current();
        final Place place = m_PlaceCurrent;
        final TicketInfo ticket = m_panelticket.getActiveTicket();
        String title = AppLocal.getIntString("message.kitchentitle");
        if (place == null || ticket == null) {
            JOptionPane.showMessageDialog(this, AppLocal.getIntString("message.kitchennotrestaurant"), title,
                    JOptionPane.INFORMATION_MESSAGE);
            return true;
        }
        if (ks == null) {
            JOptionPane.showMessageDialog(this, "Envoi en cuisine indisponible (voir le journal de la caisse).", title,
                    JOptionPane.WARNING_MESSAGE);
            return true;
        }
        if (!saveCurrentPlace()) {
            return true;   // conflit : l'utilisateur a choisi, rien n'est envoye
        }
        final String orderId = m_currentOrderId;
        final KitchenService.Actor actor = tillActor();

        if (KitchenService.pendingLines(ticket) == 0) {
            if (!resolveUncertainSends(ks, place, orderId, actor, ticket)) {
                JOptionPane.showMessageDialog(this, AppLocal.getIntString("message.kitchennothing"), title,
                        JOptionPane.INFORMATION_MESSAGE);
            }
            return true;
        }

        final JDialog wait = new JDialog(SwingUtilities.getWindowAncestor(this), title,
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        wait.add(new JLabel("  Envoi en cuisine en cours...  "));
        wait.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        wait.pack();
        wait.setLocationRelativeTo(this);
        final Object[] result = new Object[1];
        SwingWorker<Map<String, Object>, Void> worker = new SwingWorker<Map<String, Object>, Void>() {
            @Override
            protected Map<String, Object> doInBackground() throws Exception {
                return ks.sendFromTill(place.getId(), orderId, actor);
            }

            @Override
            protected void done() {
                try {
                    result[0] = get();
                } catch (java.util.concurrent.ExecutionException e) {
                    result[0] = e.getCause();
                } catch (InterruptedException e) {
                    result[0] = e;
                }
                wait.dispose();
            }
        };
        worker.execute();
        wait.setVisible(true);   // bloque l'ecran (pas la file d'evenements) pendant l'envoi

        reloadCurrentFromStore(place);
        showKitchenResult(result[0], title);
        return true;
    }

    /** Envoi "a verifier" : demande si le bon est sorti, ou s'il faut le reimprimer (DUPLICATA). */
    @SuppressWarnings("unchecked")
    private boolean resolveUncertainSends(KitchenService ks, Place place, String orderId,
            KitchenService.Actor actor, TicketInfo ticket) {
        boolean asked = false;
        for (Object o : KitchenService.sendsJson(ticket)) {
            Map<String, Object> send = (Map<String, Object>) o;
            if (!"a_verifier".equals(send.get("status"))) {
                continue;
            }
            asked = true;
            Object[] options = {"Le bon est sorti", "Reimprimer (DUPLICATA)", "Plus tard"};
            int r = JOptionPane.showOptionDialog(this, "Bon cuisine du "
                    + new java.text.SimpleDateFormat("HH:mm").format(new Date(((Number) send.get("at")).longValue()))
                    + " : resultat incertain.\n" + send.get("message") + "\n\nVerifiez en cuisine.",
                    AppLocal.getIntString("message.kitchentitle"), JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE, null, options, options[2]);
            if (r == 0 || r == 1) {
                try {
                    ks.resolveFromTill(place.getId(), orderId, (String) send.get("id"), r == 0, actor);
                } catch (ApiServer.ApiError | BasicException e) {
                    JOptionPane.showMessageDialog(this, e.getMessage(), AppLocal.getIntString("message.kitchentitle"),
                            JOptionPane.WARNING_MESSAGE);
                }
                reloadCurrentFromStore(place);
            }
        }
        return asked;
    }

    /** Recharge la commande ouverte depuis la base (apres un envoi cuisine). */
    private void reloadCurrentFromStore(Place place) {
        try {
            TableTicketStore.Snapshot snap = store.load(place.getId());
            if (snap != null && snap.getOrderId().equals(m_currentOrderId)) {
                m_currentVersion = snap.getVersion();
                m_currentOpenBytes = serialize(snap.getTicket());
                m_panelticket.setActiveTicket(snap.getTicket(), place.getName());
            }
        } catch (BasicException e) {
            new MessageInf(e).show(this);
        }
    }

    @SuppressWarnings("unchecked")
    private void showKitchenResult(Object result, String title) {
        if (result instanceof Map) {
            Map<String, Object> send = (Map<String, Object>) ((Map<String, Object>) result).get("send");
            String status = send == null ? "" : String.valueOf(send.get("status"));
            String message = send == null ? "" : String.valueOf(send.get("message"));
            String label;
            int type;
            switch (status) {
                case "confirme":
                    label = "Bon cuisine imprime (confirme).";
                    type = JOptionPane.INFORMATION_MESSAGE;
                    break;
                case "envoye":
                    label = "Bon cuisine envoye a l'imprimante.";
                    type = JOptionPane.INFORMATION_MESSAGE;
                    break;
                case "echec":
                    label = "ECHEC : le bon cuisine n'a pas ete imprime. Les articles restent a envoyer.";
                    type = JOptionPane.ERROR_MESSAGE;
                    break;
                default:
                    label = "A VERIFIER : le bon a pu sortir ou non. Rien ne sera reimprime automatiquement.";
                    type = JOptionPane.WARNING_MESSAGE;
                    break;
            }
            JOptionPane.showMessageDialog(this, label + "\n" + message, title, type);
        } else if (result instanceof ApiServer.ApiError) {
            JOptionPane.showMessageDialog(this, ((Exception) result).getMessage(), title, JOptionPane.WARNING_MESSAGE);
        } else if (result instanceof Throwable) {
            new MessageInf(MessageInf.SGN_WARNING, title, result).show(this);
        }
    }

    /** Affiche la commande d'une table que la caisse tient deja (verrou pris). */
    private void setActivePlace(Place place, TicketInfo ticket, long version) {
        m_PlaceCurrent = place;
        m_currentOrderId = ticket.getId();
        m_currentVersion = version;
        m_currentOpenBytes = serialize(ticket);
        m_panelticket.setActiveTicket(ticket, m_PlaceCurrent.getName());
        m_restaurantmap.updateGuestCount();

        try {
            dlReceipts.lockSharedTicket(m_PlaceCurrent.getId(),"locked");
        } catch (BasicException ex) {
            log.error(ex.getMessage());
        }
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
            if (!actionEnabled) {
                m_place.setDiffX(0);
            } else if (m_PlaceClipboard == null) {
                openTable();
            } else {
                moveOrMerge();
            }
        }

        /** Ouverture d'une table depuis le plan de salle (regles d'uniCenta 5.0 + verrou OLPOS). */
        private void openTable() {
            if (!lockForTill(m_place)) {
                return;
            }
            boolean opened = false;
            try {
                TableTicketStore.Snapshot snap = store.load(m_place.getId());
                if (snap == null) {                                     // It's an empty table
                    TicketInfo ticket = new TicketInfo();
                    ticket.setUser(m_App.getAppUserView().getUser().getUserInfo());
                    try {
                        long v = store.create(m_place.getId(), ticket, tillUserName(), TableLocks.Source.CAISSE.label());
                        m_place.setPeople(true);
                        m_place.setGuests(restDB.updateGuestsInTable(m_place.getId()));
                        setActivePlace(m_place, ticket, v);
                        opened = true;
                    } catch (OrderConflictException c) {
                        // creee au meme moment ailleurs (telephone ou autre poste)
                        m_place.setPeople(true);
                        new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tablefull"))
                                .show(JTicketsBagRestaurantMap.this);
                    }
                } else {                                                // Table not empty
                    TicketInfo ticket = snap.getTicket();
                    String m_lockState = dlReceipts.getLockState(m_place.getId(), null);

                    if ("locked".equals(m_lockState)) {                 // It's locked
                        JOptionPane.showMessageDialog(null,
                            AppLocal.getIntString("message.sharedticketlock"));

                        if (m_App.getAppUserView().getUser().hasPermission("sales.Override")) {       // Override it
                            int res = JOptionPane.showConfirmDialog(null
                                , AppLocal.getIntString("message.sharedticketlockoverride")
                                , AppLocal.getIntString("title.editor")
                                , JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

                            if (res == JOptionPane.YES_OPTION) {
                                m_place.setPeople(true);
                                m_PlaceClipboard = null;
                                setActivePlace(m_place, ticket, snap.getVersion());
                                opened = true;
                            }
                        }
                    } else {   // It's not locked
                        String m_user = m_App.getAppUserView().getUser().getId();
                        String ticketuser = dlReceipts.getServer(m_place.getId(), m_user);

                        if (m_App.getAppUserView().getUser().hasPermission("sales.Override") //Check User permission
                                || m_user.equals(ticketuser)) {
                            m_place.setPeople(true);
                            m_PlaceClipboard = null;
                            setActivePlace(m_place, ticket, snap.getVersion());
                            opened = true;
                        } else {
                            JOptionPane.showMessageDialog(null
                                , AppLocal.getIntString("message.sharedticket")
                                , AppLocal.getIntString("title.editor")
                                , JOptionPane.OK_OPTION);
                        }
                    }
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
            Place source = m_PlaceClipboard;                            // FROM table
            if (!lockForTill(source)) {
                return;
            }
            boolean sourceKept = false;   // true si la caisse garde la table source ouverte
            try {
                TableTicketStore.Snapshot clip = store.load(source.getId());
                if (clip == null) {                                     // table empty!
                    new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tableempty"))
                            .show(JTicketsBagRestaurantMap.this);
                    source.setPeople(false);
                    m_PlaceClipboard = null;
                    customer = null;
                    printState();
                    return;
                }
                TicketInfo ticketclip = clip.getTicket();

                if (source == m_place) {                                // same table: cancel
                    m_PlaceClipboard = null;
                    customer = null;
                    printState();
                    setActivePlace(source, ticketclip, clip.getVersion());
                    sourceKept = true;
                    return;
                }

                if (!lockForTill(m_place)) {                            // TO table
                    return;
                }
                boolean targetOpened = false;
                try {
                    TableTicketStore.Snapshot target = store.load(m_place.getId());
                    if (m_place.hasPeople()) {                          // TO table already occupied: merge
                        if (target == null) {
                            new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tableempty"))
                                    .show(JTicketsBagRestaurantMap.this);
                            m_place.setPeople(false);
                        } else if (JOptionPane.showConfirmDialog(JTicketsBagRestaurantMap.this,
                                AppLocal.getIntString("message.mergetablequestion"),
                                AppLocal.getIntString("message.mergetable"),
                                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                            TicketInfo ticket = target.getTicket();
                            if (ticket.getCustomer() == null) {
                                ticket.setCustomer(ticketclip.getCustomer());
                            }
                            for (TicketLineInfo line : ticketclip.getLines()) {
                                ticket.addLine(line);
                            }
                            long v;
                            try {
                                v = store.update(m_place.getId(), ticket, target.getOrderId(), target.getVersion(),
                                        tillUserName(), TableLocks.Source.CAISSE.label());
                            } catch (OrderConflictException c) {
                                JOptionPane.showMessageDialog(JTicketsBagRestaurantMap.this,
                                        AppLocal.getIntString("message.orderconflicttext", m_place.getName(), c.getMessage()),
                                        AppLocal.getIntString("message.orderconflicttitle"), JOptionPane.WARNING_MESSAGE);
                                return;
                            }
                            deleteMovedSource(source, clip);
                            m_place.setGuests(source.getGuests());    // Add Guests from Clipboard
                            m_PlaceClipboard = null;
                            customer = null;

                            restDB.clearCustomerNameInTable(restDB.getTableDetails(ticketclip.getId()));
                            restDB.clearWaiterNameInTable(restDB.getTableDetails(ticketclip.getId()));
                            restDB.clearTableMovedFlag(restDB.getTableDetails(ticketclip.getId()));
                            restDB.clearTicketIdInTable(restDB.getTableDetails(ticketclip.getId()));
                            restDB.clearOccupiedTable(restDB.getTableDetails(ticketclip.getId()));

                            printState();
                            setActivePlace(m_place, ticket, v);
                            targetOpened = true;
                        } else {                                        // don't want to merge
                            m_PlaceClipboard = null;
                            customer = null;
                            printState();
                            setActivePlace(source, ticketclip, clip.getVersion());
                            sourceKept = true;
                        }
                    } else {                                            // The TO table is empty: move
                        if (target == null) {
                            long v;
                            try {
                                v = store.create(m_place.getId(), ticketclip, tillUserName(), TableLocks.Source.CAISSE.label());
                            } catch (OrderConflictException c) {
                                m_place.setPeople(true);
                                new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tablefull"))
                                        .show(JTicketsBagRestaurantMap.this);
                                return;
                            }
                            m_place.setPeople(true);
                            m_place.setGuests(source.getGuests());    // Add Guests from Clipboard
                            deleteMovedSource(source, clip);
                            printState();
                            setActivePlace(m_place, ticketclip, v);
                            targetOpened = true;
                            m_PlaceClipboard = null;
                            customer = null;
                        } else {
                            new MessageInf(MessageInf.SGN_WARNING, AppLocal.getIntString("message.tablefull"))
                                    .show(JTicketsBagRestaurantMap.this);
                            m_place.setPeople(true);
                            printState();
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
        private void deleteMovedSource(Place source, TableTicketStore.Snapshot clip) throws BasicException {
            try {
                store.delete(source.getId(), clip.getOrderId(), clip.getVersion());
                source.setPeople(false);
                unlockInDatabase(source);
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
        m_jbtnLayout = new javax.swing.JButton();
        m_jbtnSave = new javax.swing.JButton();
        m_jText = new javax.swing.JLabel();
        webLblautoRefresh = new com.alee.laf.label.WebLabel();

        setLayout(new java.awt.CardLayout());

        m_jPanelMap.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        m_jPanelMap.setLayout(new java.awt.BorderLayout());

        jPanel1.setLayout(new java.awt.BorderLayout());

        jPanel2.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        jPanel2.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));

        m_jbtnReservations.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        m_jbtnReservations.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/unicenta/images/date.png"))); // NOI18N
        m_jbtnReservations.setText(AppLocal.getIntString("button.reservations")); // NOI18N
        m_jbtnReservations.setToolTipText("Open Reservations screen");
        m_jbtnReservations.setFocusPainted(false);
        m_jbtnReservations.setFocusable(false);
        m_jbtnReservations.setMargin(new java.awt.Insets(8, 14, 8, 14));
        m_jbtnReservations.setMaximumSize(new java.awt.Dimension(133, 40));
        m_jbtnReservations.setMinimumSize(new java.awt.Dimension(133, 40));
        m_jbtnReservations.setPreferredSize(new java.awt.Dimension(133, 45));
        m_jbtnReservations.setRequestFocusEnabled(false);
        m_jbtnReservations.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jbtnReservationsActionPerformed(evt);
            }
        });
        jPanel2.add(m_jbtnReservations);

        m_jbtnRefresh.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        m_jbtnRefresh.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/unicenta/images/reload.png"))); // NOI18N
        m_jbtnRefresh.setText(AppLocal.getIntString("button.reloadticket")); // NOI18N
        m_jbtnRefresh.setToolTipText("Reload table information");
        m_jbtnRefresh.setFocusPainted(false);
        m_jbtnRefresh.setFocusable(false);
        m_jbtnRefresh.setMargin(new java.awt.Insets(8, 14, 8, 14));
        m_jbtnRefresh.setMaximumSize(new java.awt.Dimension(100, 40));
        m_jbtnRefresh.setMinimumSize(new java.awt.Dimension(100, 40));
        m_jbtnRefresh.setPreferredSize(new java.awt.Dimension(100, 45));
        m_jbtnRefresh.setRequestFocusEnabled(false);
        m_jbtnRefresh.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jbtnRefreshActionPerformed(evt);
            }
        });
        jPanel2.add(m_jbtnRefresh);

        m_jbtnLayout.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        m_jbtnLayout.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/unicenta/images/movetable.png"))); // NOI18N
        m_jbtnLayout.setText(AppLocal.getIntString("button.layout")); // NOI18N
        m_jbtnLayout.setToolTipText("");
        m_jbtnLayout.setFocusPainted(false);
        m_jbtnLayout.setFocusable(false);
        m_jbtnLayout.setMargin(new java.awt.Insets(8, 14, 8, 14));
        m_jbtnLayout.setMaximumSize(new java.awt.Dimension(100, 40));
        m_jbtnLayout.setMinimumSize(new java.awt.Dimension(100, 40));
        m_jbtnLayout.setPreferredSize(new java.awt.Dimension(100, 45));
        m_jbtnLayout.setRequestFocusEnabled(false);
        m_jbtnLayout.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jbtnLayoutActionPerformed(evt);
            }
        });
        jPanel2.add(m_jbtnLayout);

        m_jbtnSave.setFont(new java.awt.Font("Arial", 0, 12)); // NOI18N
        m_jbtnSave.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/unicenta/images/filesave.png"))); // NOI18N
        m_jbtnSave.setText(AppLocal.getIntString("button.save")); // NOI18N
        m_jbtnSave.setToolTipText("");
        m_jbtnSave.setFocusPainted(false);
        m_jbtnSave.setFocusable(false);
        m_jbtnSave.setMargin(new java.awt.Insets(8, 14, 8, 14));
        m_jbtnSave.setMaximumSize(new java.awt.Dimension(100, 40));
        m_jbtnSave.setMinimumSize(new java.awt.Dimension(100, 40));
        m_jbtnSave.setPreferredSize(new java.awt.Dimension(100, 45));
        m_jbtnSave.setRequestFocusEnabled(false);
        m_jbtnSave.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                m_jbtnSaveActionPerformed(evt);
            }
        });
        jPanel2.add(m_jbtnSave);

        m_jText.setBackground(new java.awt.Color(255, 255, 255));
        m_jText.setFont(new java.awt.Font("Arial", 0, 14)); // NOI18N
        m_jText.setForeground(new java.awt.Color(0, 153, 255));
        m_jText.setOpaque(true);
        jPanel2.add(m_jText);

        jPanel1.add(jPanel2, java.awt.BorderLayout.LINE_START);

        webLblautoRefresh.setBackground(new java.awt.Color(255, 51, 51));
        webLblautoRefresh.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("pos_messages"); // NOI18N
        webLblautoRefresh.setText(bundle.getString("label.autoRefreshTableMapTimerON")); // NOI18N
        webLblautoRefresh.setFont(new java.awt.Font("Arial", 0, 14)); // NOI18N
        jPanel1.add(webLblautoRefresh, java.awt.BorderLayout.CENTER);

        m_jPanelMap.add(jPanel1, java.awt.BorderLayout.NORTH);

        add(m_jPanelMap, "map");
    }// </editor-fold>//GEN-END:initComponents

    private void m_jbtnRefreshActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jbtnRefreshActionPerformed

        loadTickets();     
        printState();   
    }//GEN-LAST:event_m_jbtnRefreshActionPerformed

    private void m_jbtnReservationsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jbtnReservationsActionPerformed
        showView("res");
        m_jreservations.activate();
    }//GEN-LAST:event_m_jbtnReservationsActionPerformed

    private void m_jbtnLayoutActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jbtnLayoutActionPerformed
        if (java.util.ResourceBundle.getBundle("pos_messages")
                .getString("button.layout").equals(m_jbtnLayout.getText())) {
            actionEnabled = false;
            m_jbtnSave.setVisible(true);
            m_jbtnLayout.setText(java.util.ResourceBundle
                    .getBundle("pos_messages").getString("button.disablelayout"));
            
            m_aplaces.stream().filter((pl) -> (transBtns)).map((pl) -> {
                pl.getButton().setOpaque(true);
                return pl;
            }).map((pl) -> {
                pl.getButton().setContentAreaFilled(true);
                return pl;
            }).forEachOrdered((pl) -> {
                pl.getButton().setBorderPainted(true);
            });
        } else {
            actionEnabled = true;
            m_jbtnSave.setVisible(false);
            m_jbtnLayout.setText(java.util.ResourceBundle
                    .getBundle("pos_messages").getString("button.layout"));

            m_aplaces.stream().filter((pl) -> (transBtns)).map((pl) -> {
                pl.getButton().setOpaque(false);
                return pl;
            }).map((pl) -> {
                pl.getButton().setContentAreaFilled(false);
                return pl;
            }).forEachOrdered((pl) -> {
                pl.getButton().setBorderPainted(false);
            });
        }
    }//GEN-LAST:event_m_jbtnLayoutActionPerformed

    private void m_jbtnSaveActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_m_jbtnSaveActionPerformed
        m_aplaces.forEach((pl) -> {
            try {
                dlSystem.updatePlaces(pl.getX(), pl.getY(), pl.getId());
            } catch (BasicException ex) {
                log.error(ex.getMessage());
            }
        });
    }//GEN-LAST:event_m_jbtnSaveActionPerformed
    
    
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel m_jPanelMap;
    private javax.swing.JLabel m_jText;
    private javax.swing.JButton m_jbtnLayout;
    private javax.swing.JButton m_jbtnRefresh;
    private javax.swing.JButton m_jbtnReservations;
    private javax.swing.JButton m_jbtnSave;
    private com.alee.laf.label.WebLabel webLblautoRefresh;
    // End of variables declaration//GEN-END:variables
    
}
