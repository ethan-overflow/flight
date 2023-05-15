package flightapp;

import javax.naming.spi.ResolveResult;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * Runs queries against a back-end database
 */
public class Query extends QueryAbstract {
  //
  // Canned queries
  //
  private static final String FLIGHT_CAPACITY_SQL = "SELECT capacity FROM FLIGHTS WHERE fid = ?";
  private static final String USER_BALANCE_SQL = "SELECT balance FROM Users_pricecs WHERE username = ?";
  private static final String FLIGHT_PRICE_SQL = "SELECT price FROM FLIGHTS WHERE fid = ?";
  private static final String SET_BALANCE_SQL = "UPDATE Users_pricecs SET balance = ? WHERE username = ?";
  private static final String SET_RESERVATION_PAID_SQL = "UPDATE Reservations_pricecs SET is_paid = 1 " +
          "WHERE reservation_id = ? AND username = ?";
  private static final String RESERVATION_COUNT_SQL = "SELECT COUNT(*) AS res_count " +
          "FROM Reservations_pricecs " +
          "WHERE (first_fid = ? OR second_fid = ?)";
  private static final String CLEAR_USERS = "DELETE FROM Users_pricecs";
  private static final String CLEAR_RESERVATIONS = "DELETE FROM Reservations_pricecs";

  private static final String F1_SELECT_ATTRIBUTES =
          "F1.fid AS F1_fid,F1.day_of_month AS F1_day_of_month,F1.carrier_id AS F1_carrier_id," +
                  "F1.flight_num AS F1_flight_num,F1.origin_city AS F1_origin_city," +
                  "F1.dest_city AS F1_dest_city,F1.actual_time AS F1_actual_time," +
                  "F1.capacity AS F1_capacity,F1.price AS F1_price";

  private final String F2_SELECT_ATTRIBUTES =
          "F2.fid AS F2_fid,F2.day_of_month AS F2_day_of_month,F2.carrier_id AS F2_carrier_id," +
                  "F2.flight_num AS F2_flight_num,F2.origin_city AS F2_origin_city," +
                  "F2.dest_city AS F2_dest_city,F2.actual_time AS F2_actual_time," +
                  "F2.capacity AS F2_capacity,F2.price AS F2_price, F1.actual_time + F2.actual_time AS total_time";
  private PreparedStatement flightCapacityStmt;
  private PreparedStatement userBalanceStmt;
  private PreparedStatement flightPriceStmt;
  private PreparedStatement setBalanceStmt;
  private PreparedStatement setReservationPaidStmt;
  private PreparedStatement reservationCountStmt;
  private PreparedStatement clearUsersStmt;
  private PreparedStatement clearReservationsStmt;

  //
  // Instance variables
  //
  private boolean loggedIn = false;
  private String loggedInUser = null;
  private HashMap<Integer, Itinerary> recentItineraryResults;

  protected Query() throws SQLException, IOException {
    recentItineraryResults = new HashMap<>();
    prepareStatements();
  }

  /**
   * Clear the data in any custom tables created.
   *
   * WARNING! Do not drop any tables and do not clear the flights table.
   */
  public void clearTables() {
    try {
      clearReservationsStmt.execute();
      clearUsersStmt.execute();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /*
   * prepare all the SQL statements in this method.
   */
  private void prepareStatements() throws SQLException {
    userBalanceStmt = conn.prepareStatement(USER_BALANCE_SQL);
    flightPriceStmt = conn.prepareStatement(FLIGHT_PRICE_SQL);
    flightCapacityStmt = conn.prepareStatement(FLIGHT_CAPACITY_SQL);
    setBalanceStmt = conn.prepareStatement(SET_BALANCE_SQL);
    setReservationPaidStmt = conn.prepareStatement(SET_RESERVATION_PAID_SQL);
    reservationCountStmt = conn.prepareStatement(RESERVATION_COUNT_SQL);

    clearUsersStmt = conn.prepareStatement(CLEAR_USERS);
    clearReservationsStmt = conn.prepareStatement(CLEAR_RESERVATIONS);
  }

  /**
   * Finds the existing salted hash for the given username
   * @param username username to get salted hash bytes from
   * @return byte array of salted hash for given username, or null if username doesn't exist
   */
  private byte[] getUserSaltyHash(String username) {
    String getSaltyHashQuery = "SELECT password FROM Users_pricecs WHERE username = ?";
    byte[] saltyHashBytes = null;
    try {
      PreparedStatement getSaltyHashStmt = conn.prepareStatement(getSaltyHashQuery);
      getSaltyHashStmt.setString(1, username);
      ResultSet usernameByteResults = getSaltyHashStmt.executeQuery();
      if (usernameByteResults.next()) {
        saltyHashBytes = usernameByteResults.getBytes("password");
        usernameByteResults.close();
        return saltyHashBytes;
      }
      else {
        return null;
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    return null;
  }

  /**
   * Takes a user's username and password and attempts to log the user in.
   *
   * @param username user's username
   * @param password user's password
   *
   * @return If someone has already logged in, then return "User already logged in\n".  For all
   *         other errors, return "Login failed\n". Otherwise, return "Logged in as [username]\n".
   */
  public String transaction_login(String username, String password) {
    final String loginFailure = "Login failed\n";
    final String loginSuccess = "Logged in as " + username + "\n";

    if (loggedIn) {
      return "User already logged in\n";
    }

    byte[] userSaltyHash = getUserSaltyHash(username);
    // Ensure username exists
    if (userSaltyHash == null) {
      return loginFailure;
    }

    boolean samePassword = PasswordUtils.plaintextMatchesSaltedHash(password, userSaltyHash);
    if (samePassword) {
      recentItineraryResults.clear();
      loggedIn = true;
      this.loggedInUser = username;
      return loginSuccess;
    }
    return loginFailure;
  }

  /**
   * Checks if username already exists
   * @param username to check if exists in Users table
   * @return true if user already exists, false otherwise
   */
  private boolean userExists(String username) throws SQLException {
    int userExists = 0;
    String checkUsernameQuery = "SELECT Count(username) AS 'exists' FROM Users_pricecs WHERE username = ?";
    PreparedStatement givenUsernameCountStmt = conn.prepareStatement(checkUsernameQuery);
    givenUsernameCountStmt.setString(1, username);
    ResultSet usernameCountResult = givenUsernameCountStmt.executeQuery();
    usernameCountResult.next();
    userExists = usernameCountResult.getInt("exists");
    usernameCountResult.close();

    if (userExists > 1) {
      System.out.println("Something is seriously wrong... (multiple identical usernames in table)");
      System.exit(0);
    }
    return userExists == 1;
  }

  /**
   * Creates a new user in the Users table
   * @param username unique username for User
   * @param saltyHash the password after being salted and hashed, in bytes and prefixed by the salt
   * @param initAmount Initial balance for user to be inserted with
   * @throws SQLException on SQL Execution error
   */
  private void insertUser(String username, byte[] saltyHash, int initAmount) throws SQLException {
    String insertQuery = "INSERT INTO Users_pricecs VALUES(?, ?, ?)";

    PreparedStatement insertStmt = conn.prepareStatement(insertQuery);
    insertStmt.setString(1, username);
    insertStmt.setBytes(2, saltyHash);
    insertStmt.setInt(3, initAmount);
    insertStmt.execute();
  }

  /**
   * Implement the create user function.
   *
   * @param username   new user's username. User names are unique the system.
   * @param password   new user's password.
   * @param initAmount initial amount to deposit into the user's account, should be >= 0 (failure
   *                   otherwise).
   *
   * @return either "Created user {@code username}\n" or "Failed to create user\n" if failed.
   */
  public String transaction_createCustomer(String username, String password, int initAmount) {
    final String failure = "Failed to create user\n";
    final String success = "Created user " + username + "\n";

    // Validate input
    if (username.length() < 1 || initAmount < 0) {
      return failure;
    }

    // Start transaction: read then write
    try {
      conn.setAutoCommit(false);
      // Ensure username is not already in system
      if (userExists(username)) {
        conn.commit();
        conn.setAutoCommit(true);
        return failure;
      }

      // Insert new user
      byte[] saltyHash = PasswordUtils.saltAndHashPassword(password);
      insertUser(username, saltyHash, initAmount);
      conn.commit();
      conn.setAutoCommit(true);
      return success;
    } catch (SQLException e) {
      if (isDeadlock(e)) {
        try {
          // Try again!
          conn.rollback();
          return transaction_createCustomer(username, password, initAmount);
        } catch (Exception sqlFailureE) {
          sqlFailureE.printStackTrace();
        }
      }
    }
    return failure;
  }

  /**
   * Parses a ResultSet with all necessary flight attributes starting with the given prefix
   * into a new Flight instance.
   * @param resultSet ResultSet with rows of data with necessary flight columns. Must be valid iterator when passed.
   * @param prefix All selected Flight attribute column names MUST begin with prefix
   * @return new Flight instance from given ResultSet
   * @throws SQLException
   */
  private Flight getFlightFromResult(ResultSet resultSet, String prefix) throws SQLException {
    int result_fid = resultSet.getInt(prefix + "fid");
    int result_dayOfMonth = resultSet.getInt(prefix + "day_of_month");
    String result_carrierId = resultSet.getString(prefix + "carrier_id");
    String result_flightNum = resultSet.getString(prefix + "flight_num");
    String result_originCity = resultSet.getString(prefix + "origin_city");
    String result_destCity = resultSet.getString(prefix + "dest_city");
    int result_time = resultSet.getInt(prefix + "actual_time");
    int result_capacity = resultSet.getInt(prefix + "capacity");
    int result_price = resultSet.getInt(prefix + "price");
    return new Flight(result_fid, result_dayOfMonth, result_carrierId, result_flightNum,
            result_originCity, result_destCity, result_time, result_capacity, result_price);
  }

  /**
   * Parses a ResultSet into a list of Itineraries
   * @param resultSet the ResultSet to parse
   * @param isDirect true if the ResultSet contains fields for both F1 and F2
   * @return ArrayList of either direct or indirect Itineraries depending on isDirect from the given ResultSet
   *         or null if parsing failed
   */
  private ArrayList<Itinerary> parseResultSet(ResultSet resultSet, boolean isDirect) {
    try {
      ArrayList<Itinerary> itineraryList = new ArrayList<>();
      Itinerary curItin;
      while (resultSet.next()) {
        // First flight
        Flight curFlight = getFlightFromResult(resultSet, "F1_");

        if (!isDirect) {
          // Second flight if indirect Itinerary
          Flight curFlight2 = getFlightFromResult(resultSet, "F2_");

          curItin = new Itinerary(curFlight, curFlight2);
        } else {
          curItin = new Itinerary(curFlight);
        }

        itineraryList.add(curItin);
      }
      return itineraryList;

    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Returns an ArrayList of top n direct Itineraries sorted by time
   * @param originCity Flight origin
   * @param destCity Flight destination
   * @param day day of month (1-31)
   * @param numberOfItineraries number of max Itineraries to return in ArrayList
   * @return ArrayList of top n direct Itineraries sorted by time, or null if failed
   */
  private ArrayList<Itinerary> getItineraryList(String originCity,
                                                String destCity, int day,
                                                int numberOfItineraries,
                                                boolean isDirect) {
    ArrayList<Itinerary> itineraryList;
    try {
      String flightSearchQuery;
      if (isDirect) {
        // Query to get all direct flights
        flightSearchQuery = "SELECT TOP (?) " +
                F1_SELECT_ATTRIBUTES + " " +
                "FROM FLIGHTS AS F1 " +
                "WHERE F1.origin_city = ? AND " +
                "F1.dest_city = ? AND " +
                "F1.day_of_month = ? AND " +
                "F1.canceled = 0 " +
                "ORDER BY F1_actual_time ASC";
      } else {
        // Query to get all pairs of 2 flights to a location (indirect Itinerary)
        flightSearchQuery = "SELECT TOP (?) " +
                F1_SELECT_ATTRIBUTES + ", " +
                F2_SELECT_ATTRIBUTES + " " +
                "FROM FLIGHTS AS F1, FLIGHTS AS F2 " +
                "WHERE F1.dest_city = F2.origin_city AND " +
                "F1.origin_city = ? AND " +
                "F2.dest_city = ? AND " +
                "F1.canceled = 0 AND " +
                "F2.canceled = 0 AND " +
                "F1.day_of_month = ? AND " +
                "F2.day_of_month = F1.day_of_month AND " +
                "F1.origin_city != F2.dest_city " +
                "ORDER BY total_time ASC";
      }

      // Set parameters
      PreparedStatement flightSearchStmt = conn.prepareStatement(flightSearchQuery);
      flightSearchStmt.setInt(1, numberOfItineraries);
      flightSearchStmt.setString(2, originCity);
      flightSearchStmt.setString(3, destCity);
      flightSearchStmt.setInt(4, day);

      ResultSet searchResults = flightSearchStmt.executeQuery();

      // Build Itinerary list depending on isDirect
      itineraryList = parseResultSet(searchResults, isDirect);
      if (itineraryList == null) {
        return null;
      }

      searchResults.close();
      return itineraryList;

    } catch (SQLException e) {
      return null;
    }
  }

  /**
   * Assigns all Itineraries an id starting from 0, and inserts them into the current session's recent search
   * @param results An ArrayList of Itineraries sorted by the Itineraries' times
   * @return A string representation of the given Itineraries with the id
   */
  private String initializeItineraries(ArrayList<Itinerary> results) {
    StringBuffer sb = new StringBuffer();

    int itinId = 0;
    for (Itinerary i : results) {
      i.id = itinId;
      recentItineraryResults.put(itinId, i);
      itinId++;
      sb.append(i.toString());
      sb.append("\n");
    }

    return sb.toString();
  }

  /**
   * Implement the search function.
   *
   * Searches for flights from the given origin city to the given destination city, on the given
   * day of the month. If {@code directFlight} is true, it only searches for direct flights,
   * otherwise is searches for direct flights and flights with two "hops." Only searches for up
   * to the number of itineraries given by {@code numberOfItineraries}.
   *
   * The results are sorted based on total flight time.
   *
   * @param originCity
   * @param destinationCity
   * @param directFlight        if true, then only search for direct flights, otherwise include
   *                            indirect flights as well
   * @param dayOfMonth
   * @param numberOfItineraries number of itineraries to return, must be positive
   *
   * @return If no itineraries were found, return "No flights match your selection\n". If an error
   *         occurs, then return "Failed to search\n".
   *
   *         Otherwise, the sorted itineraries printed in the following format:
   *
   *         Itinerary [itinerary number]: [number of flights] flight(s), [total flight time]
   *         minutes\n [first flight in itinerary]\n ... [last flight in itinerary]\n
   *
   *         Each flight should be printed using the same format as in the {@code Flight} class.
   *         Itinerary numbers in each search should always start from 0 and increase by 1.
   *
   * @see Flight#toString()
   */
  public String transaction_search(String originCity, String destinationCity,
                                   boolean directFlight, int dayOfMonth,
                                   int numberOfItineraries) {

    ArrayList<Itinerary> directItins = getItineraryList(originCity, destinationCity, dayOfMonth,
            numberOfItineraries, true);
    if (directItins == null) {
      return "Failed to search\n";
    }
    if (directItins.isEmpty()) {
      return "No flights match your selection\n";
    }

    // If filtered by direct, just return the direct ones
    if (directFlight) {
      Collections.sort(directItins);
      return initializeItineraries(directItins);
    } else {
      // If indirect Itineraries are allowed, but there are enough direct Itineraries to show
      if (directItins.size() == numberOfItineraries) {
        Collections.sort(directItins);
        return initializeItineraries(directItins);
      }
      // Figure out how many indirect Itineraries to include
      int numIndirectItinsToAdd = numberOfItineraries - directItins.size();

      ArrayList<Itinerary> finalResultItins = new ArrayList<>();
      ArrayList<Itinerary> indirectItins = getItineraryList(originCity, destinationCity, dayOfMonth,
              numberOfItineraries, false);

      if (indirectItins.isEmpty() && directItins.isEmpty()) {
        return "No flights match your selection\n";
      }
      // Add all direct flights we have first
      finalResultItins.addAll(directItins);

      // Then add indirect flights
      for (int i = 0; i < numIndirectItinsToAdd; i++) {
        finalResultItins.add(indirectItins.get(i));
      }
      Collections.sort(finalResultItins);
      return initializeItineraries(finalResultItins);
    }
  }

  /**
   * Checks if given user already has a reservation on the given day
   * @param username user to check reservation for
   * @param dayOfMonth day to check if reserved
   * @return true if username already has reservation booked on dayOfMonth, false otherwise
   */
  boolean reservationDayConflicts(String username, int dayOfMonth) throws SQLException {

    String conflictCount = "SELECT COUNT(R.username) AS conflict_count " +
            "FROM Reservations_pricecs AS R, Flights AS F, Users_pricecs AS U " +
            "WHERE R.first_fid = F.fid AND " +
            "R.username = U.username AND " +
            "U.username = ? AND " +
            "F.day_of_month = ?";
    PreparedStatement conflictStmt = conn.prepareStatement(conflictCount);
    conflictStmt.setString(1, username);
    conflictStmt.setInt(2, dayOfMonth);

    ResultSet result = conflictStmt.executeQuery();
    result.next();
    boolean isConflict = result.getInt("conflict_count") > 0;
    result.close();
    return isConflict;
  }

  /**
   *
   * @return Max id found in table + 1, i.e. 0 if no reservations in table OR
   * -1 if error executing SQL
   */
  int getNextReservationId() throws SQLException {

    String maxIdQuery = "SELECT COALESCE(MAX(reservation_id),0) AS max_id " +
            "FROM Reservations_pricecs";
    PreparedStatement maxIdStmt = conn.prepareStatement(maxIdQuery);
    ResultSet result = maxIdStmt.executeQuery();
    result.next();
    int nextId = result.getInt("max_id") + 1;
    result.close();
    return nextId;
  }

  /**
   * Inserts a new Reservation row for the given values
   * @param username user who booked the reservation
   * @param resId unique id for res
   * @param fid1 first flight fid
   * @param fid2 second flight fid, or null if direct
   * @return true if insert succeeded, false otherwise
   */
  boolean createReservation(String username, int resId, Integer fid1, Integer fid2) throws SQLException {

      String insertReservationQuery = "INSERT INTO Reservations_pricecs VALUES (?,?,?,?,?,0)";
      PreparedStatement reservationStmt = conn.prepareStatement(insertReservationQuery);
      reservationStmt.setInt(1, resId);
      reservationStmt.setString(2, username);
      reservationStmt.setInt(3, fid1);
      if (fid2 == null) {
        reservationStmt.setNull(4, Types.INTEGER);
        reservationStmt.setInt(5, 1);
      } else {
        reservationStmt.setInt(4, fid2);
        reservationStmt.setInt(5, 0);
      }

      reservationStmt.execute();
      return true;
  }

  /**
   * Checks if given flight is full
   * @param fid fid of flight to check if full
   * @return true if the given fid's flight is at capacity
   * @throws SQLException
   */
  private boolean flightIsFull(int fid) throws SQLException {
    int numReserved = getReservationCountForFLight(fid);
    int capacity = checkFlightCapacity(fid);
    return numReserved >= capacity;
  }

  /**
   * Implements the book itinerary function.
   *
   * @param itineraryId ID of the itinerary to book. This must be one that is returned by search
   *                    in the current session.
   *
   * @return If the user is not logged in, then return "Cannot book reservations, not logged
   *         in\n". If the user is trying to book an itinerary with an invalid ID or without
   *         having done a search, then return "No such itinerary {@code itineraryId}\n". If the
   *         user already has a reservation on the same day as the one that they are trying to
   *         book now, then return "You cannot book two flights in the same day\n". For all
   *         other errors, return "Booking failed\n".
   *
   *         If booking succeeds, return "Booked flight(s), reservation ID: [reservationId]\n"
   *         where reservationId is a unique number in the reservation system that starts from
   *         1 and increments by 1 each time a successful reservation is made by any user in
   *         the system.
   */
  public String transaction_book(int itineraryId) {
    final String failure = "Booking failed\n";
    if (!loggedIn) {
      return "Cannot book reservations, not logged in\n";
    }
    // 1. Get Itinerary from id, ensure its valid
    Itinerary itineraryToBook = recentItineraryResults.get(itineraryId);
    if (recentItineraryResults.isEmpty() || itineraryToBook == null) {
      return "No such itinerary " + itineraryId + "\n";
    }

    // Transaction: Read max(id), Read reservation count, Write to reservations
    try {
      conn.setAutoCommit(false);

      // 2. Ensure user does not have reservation on that day already
      int dayToBook = itineraryToBook.flight1.dayOfMonth;
      if (reservationDayConflicts(loggedInUser, dayToBook)) {
        conn.commit();
        conn.setAutoCommit(true);
        return "You cannot book two flights in the same day\n";
      }

      // 3. Get latest reservation id so we can add 1
      int nextResId = getNextReservationId();
      if (nextResId == -1) {
        return failure;
      }

      // 4. Ensure not at capacity, Insert reservation
      Integer firstFid = itineraryToBook.flight1.fid;
      Integer secondFid = null;
      if (!itineraryToBook.isDirect) {
        secondFid = itineraryToBook.flight2.fid;
      }

      boolean firstFlightFull = flightIsFull(firstFid);
      boolean secondFlightFull = false;
      if (secondFid != null) {
        secondFlightFull = flightIsFull(secondFid);
      }
      if (firstFlightFull || secondFlightFull) {
        conn.commit();
        conn.setAutoCommit(true);
        return failure;
      }

      boolean success = createReservation(loggedInUser, nextResId, firstFid, secondFid);
      if (!success) {
        conn.commit();
        conn.setAutoCommit(true);
        return failure;
      }

      conn.commit();
      conn.setAutoCommit(true);
      return "Booked flight(s), reservation ID: " + nextResId + "\n";

    } catch (SQLException e) {
      if (isDeadlock(e)) {
        try {
          // Try again!
          conn.rollback();
          return transaction_book(itineraryId);
        } catch (Exception sqlFailureE) {
          sqlFailureE.printStackTrace();
        }
      }
    }
    return failure;
  }

  /**
   * Implements the pay function.
   *
   * @param reservationId the reservation to pay for.
   *
   * @return If no user has logged in, then return "Cannot pay, not logged in\n". If the
   *         reservation is not found / not under the logged in user's name, then return
   *         "Cannot find unpaid reservation [reservationId] under user: [username]\n".  If
   *         the user does not have enough money in their account, then return
   *         "User has only [balance] in account but itinerary costs [cost]\n".  For all other
   *         errors, return "Failed to pay for reservation [reservationId]\n"
   *
   *         If successful, return "Paid reservation: [reservationId] remaining balance:
   *         [balance]\n" where [balance] is the remaining balance in the user's account.
   */
  public String transaction_pay(int reservationId) {
    if (!loggedIn) {
      return "Cannot pay, not logged in\n";
    }

    // Transaction: Read is_paid, Read user balance, write to balance, Write to is_paid
    try {
      conn.setAutoCommit(false);
      // 1. Verify that reservation id exists
      String selIdRowQuery = "SELECT *" +
              "FROM Reservations_pricecs " +
              "WHERE reservation_id = ? AND " +
              "username = ?";
      PreparedStatement foundRes = conn.prepareStatement(selIdRowQuery);
      foundRes.setInt(1, reservationId);
      foundRes.setString(2, loggedInUser);
      ResultSet result = foundRes.executeQuery();
      boolean found = result.next();
      if (!found) {
        conn.commit();
        conn.setAutoCommit(true);
        return  "Cannot find unpaid reservation " + reservationId + " under user: " + loggedInUser + "\n";
      }
      boolean paid = result.getInt("is_paid") == 1;

      // 2. Verify user has not already paid for this reservation
      if (paid) {
        conn.commit();
        conn.setAutoCommit(true);
        return  "Cannot find unpaid reservation " + reservationId + " under user: " + loggedInUser + "\n";
      }

      // 3. Verify user has enough money to pay for reservation
      int balance = getCurrentUserBalance();

      int fid1 = result.getInt("first_fid");
      int fid2 = result.getInt("second_fid");
      int price = 0;
      price += getFlightPrice(fid1);
      if (fid2 != 0) {
        price += getFlightPrice(fid2);
      }
      if (balance < price) {
        conn.commit();
        conn.setAutoCommit(true);
        return "User has only " + balance + " in account but itinerary costs " + price + "\n";
      }

      // 4. Deduct the cost of the itinerary from the user's account
      setCurrentBalance(balance - price);

      // 5. Set paid boolean to 1 for reservation
      setReservationPaid(reservationId);
      conn.commit();
      conn.setAutoCommit(true);
      return "Paid reservation: " + reservationId + " remaining balance: " + (balance - price) + "\n";
    } catch (SQLException e) {
      if (isDeadlock(e)) {
        try {
          // Try again!
          conn.rollback();
          return transaction_pay(reservationId);
        } catch (Exception sqlFailureE) {
          sqlFailureE.printStackTrace();
        }
      }
    }
    return "Failed to pay for reservation " + reservationId + "\n";
  }

  /**
   * Gets the List of the current user's booked reservations
   * @return ArrayList of Reservations booked by the user currently logged in
   */
  private ArrayList<Reservation> getCurrentReservations() {
    try {
      ArrayList<Reservation> reservations = new ArrayList<>();
      String selResQuery = "SELECT R.*, " + F1_SELECT_ATTRIBUTES + ", " + F2_SELECT_ATTRIBUTES + " " +
              "FROM Reservations_pricecs AS R " +
              "INNER JOIN FLIGHTS AS F1 ON R.first_fid = F1.fid " +
              "LEFT JOIN FLIGHTS AS F2 ON R.second_fid = F2.fid " +
              "WHERE R.username = ? " +
              "ORDER BY R.reservation_id ASC";
      PreparedStatement resStmt = conn.prepareStatement(selResQuery);
      resStmt.setString(1, loggedInUser);
      ResultSet resultSet = resStmt.executeQuery();

      while (resultSet.next()) {
        Itinerary curItin;
        // First flight
        Flight curFlight = getFlightFromResult(resultSet, "F1_");
        boolean isDirect = false;
        if (resultSet.getInt("is_direct") == 1) {
          isDirect = true;
        }
        if (!isDirect) {
          // Second flight if indirect Itinerary
          Flight curFlight2 = getFlightFromResult(resultSet, "F2_");
          curItin = new Itinerary(curFlight, curFlight2);
        } else {
          curItin = new Itinerary(curFlight);
        }
        int paid = resultSet.getInt("is_paid");
        int id = resultSet.getInt("reservation_id");
        Reservation curReservation = new Reservation(loggedInUser, curItin, paid, id);
        reservations.add(curReservation);
      }
      resultSet.close();
      return reservations;
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  /**
   * Implements the reservations function.
   *
   * @return If no user has logged in, then return "Cannot view reservations, not logged in\n" If
   *         the user has no reservations, then return "No reservations found\n" For all other
   *         errors, return "Failed to retrieve reservations\n"
   *
   *         Otherwise return the reservations in the following format:
   *
   *         Reservation [reservation ID] paid: [true or false]:\n [flight 1 under the
   *         reservation]\n [flight 2 under the reservation]\n Reservation [reservation ID] paid:
   *         [true or false]:\n [flight 1 under the reservation]\n [flight 2 under the
   *         reservation]\n ...
   *
   *         Each flight should be printed using the same format as in the {@code Flight} class.
   *
   * @see Flight#toString()
   */
  public String transaction_reservations() {
    if (!loggedIn) {
      return "Cannot view reservations, not logged in\n";
    }
    ArrayList<Reservation> currentReservations = getCurrentReservations();
    if (currentReservations == null) {
      return "Failed to retrieve reservations\n";
    }
    if (currentReservations.isEmpty()) {
      return "No reservations found\n";
    }

    StringBuffer sb = new StringBuffer();
    for (Reservation r : currentReservations) {
      sb.append(r.toString());
      sb.append("\n");
    }
    return sb.toString();
  }

  /**
   * Utility function to set is_paid boolean to true for a Reservation
   * @param reservationId id for reservation to set paid
   * @throws SQLException on SQL execution error
   */
  private void setReservationPaid(int reservationId) throws SQLException {
    setReservationPaidStmt.clearParameters();
    setReservationPaidStmt.setInt(1, reservationId);
    setReservationPaidStmt.setString(2, loggedInUser);

    setReservationPaidStmt.executeUpdate();
  }

  /**
   * Sets the balance of the currently logged in user
   * @param newBalance new balance to set
   * @throws SQLException on SQL execution error
   */
  private void setCurrentBalance(int newBalance) throws SQLException {
    setBalanceStmt.clearParameters();
    setBalanceStmt.setInt(1, newBalance);
    setBalanceStmt.setString(2, loggedInUser);

    setBalanceStmt.executeUpdate();
  }

  /**
   * Returns the price of a given flight
   * @param fid fid of flight to retrieve price for
   * @return price of flight with id fid
   * @throws SQLException on SQL error
   */
  private int getFlightPrice(int fid) throws SQLException {
    flightPriceStmt.clearParameters();
    flightPriceStmt.setInt(1, fid);

    ResultSet results = flightPriceStmt.executeQuery();
    results.next();
    int price = results.getInt("price");
    results.close();

    return price;
  }

  /**
   * Utility function to get the balance of the user currently logged in to this session
   * @return integer balance of money for current user
   * @throws SQLException on SQL execution error
   */
  private int getCurrentUserBalance() throws SQLException {
    userBalanceStmt.clearParameters();
    userBalanceStmt.setString(1, loggedInUser);

    ResultSet results = userBalanceStmt.executeQuery();
    results.next();
    int balance = results.getInt("balance");
    results.close();

    return balance;
  }

  /**
   * Utility function to get the number of booked reservations for a given flight fid
   * @param fid fid of flight to count bookings for
   * @return number of booked reservations for flight
   * @throws SQLException on SQL execution error
   */
  private int getReservationCountForFLight(int fid) throws SQLException {
    reservationCountStmt.clearParameters();
    reservationCountStmt.setInt(1, fid);
    reservationCountStmt.setInt(2, fid);

    ResultSet results = reservationCountStmt.executeQuery();
    results.next();
    int numReservations = results.getInt("res_count");
    results.close();

    return numReservations;
  }

  /**
   * Example utility function that uses prepared statements
   */
  private int checkFlightCapacity(int fid) throws SQLException {
    flightCapacityStmt.clearParameters();
    flightCapacityStmt.setInt(1, fid);

    ResultSet results = flightCapacityStmt.executeQuery();
    results.next();
    int capacity = results.getInt("capacity");
    results.close();

    return capacity;
  }

  /**
   * Utility function to determine whether an error was caused by a deadlock
   */
  private static boolean isDeadlock(SQLException e) {
    return e.getErrorCode() == 1205;
  }

  /**
   * A class to store information about a single flight
   */
  class Flight {
    public int fid;
    public int dayOfMonth;
    public String carrierId;
    public String flightNum;
    public String originCity;
    public String destCity;
    public int time;
    public int capacity;
    public int price;

    Flight(int id, int day, String carrier, String fnum, String origin, String dest, int tm,
           int cap, int pri) {
      fid = id;
      dayOfMonth = day;
      carrierId = carrier;
      flightNum = fnum;
      originCity = origin;
      destCity = dest;
      time = tm;
      capacity = cap;
      price = pri;
    }

    @Override
    public String toString() {
      return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: "
              + flightNum + " Origin: " + originCity + " Dest: " + destCity + " Duration: " + time
              + " Capacity: " + capacity + " Price: " + price;
    }
  }

  /**
   * A class to store information about an Itinerary
   * Can consist of 1 or 2 flights
   * Must have id initialized externally
   */
  class Itinerary implements Comparable<Itinerary> {
    public Flight flight1;
    public Flight flight2;
    public boolean isDirect;
    public int id;
    public int time;
    Itinerary(Flight f1) {
      this.flight1 = f1;
      this.flight2 = null;
      this.isDirect = true;
      this.time = f1.time;
      this.id = -1;
    }
    Itinerary(Flight f1, Flight f2) {
      this.flight1 = f1;
      this.flight2 = f2;
      this.isDirect = false;
      this.time = f1.time + f2.time;
      this.id = -1;
    }

    @Override
    public String toString() {
      if (this.id == -1) {
        System.out.println("WARNING: ID UNINITIALIZED");
      }
      int num_flights = 1;
      if (!this.isDirect)
        num_flights = 2;

      String outStr = "Itinerary " + this.id + ": " + num_flights + " flight(s), " + this.time + " minutes\n" +
              this.flight1.toString();

      if (!this.isDirect)
        outStr += "\n" + this.flight2.toString();

      return outStr;
    }

    @Override
    public int compareTo(Itinerary o) {
      // Direct vs direct
      if (this.isDirect && o.isDirect) {
        int result = Integer.compare(this.flight1.time, o.flight1.time);
        if (result == 0) {
          return Integer.compare(this.flight1.fid, o.flight1.fid);
        }
        return result;
      }
      // Direct vs Indirect
      else if (this.isDirect) {
        int result = Integer.compare(this.flight1.time, o.flight1.time + o.flight2.time);
        if (result == 0) {
          return Integer.compare(this.flight1.fid, o.flight1.fid);
        }
        return result;
      }
      // Indirect vs direct
      else if (o.isDirect) {
        int result = Integer.compare(this.flight1.time + this.flight2.time, o.flight1.time);
        if (result == 0) {
          return Integer.compare(this.flight1.fid, o.flight1.fid);
        }
        return result;
      }
      // both indirect
      else {
        int result = Integer.compare(this.flight1.time + this.flight2.time,
                o.flight1.time + o.flight2.time);
        if (result == 0) {
          int fidResult = Integer.compare(this.flight1.fid, o.flight1.fid);
          if (fidResult == 0) {
            return Integer.compare(this.flight2.fid, o.flight2.fid);
          }
          return fidResult;
        }
        return  result;
      }
    }
  }

  /**
   * Represents an Itinerary that has been booked by a user
   */
  class Reservation {
    public String username;
    public Itinerary itinerary;
    public int paid;
    public int id;
    public Reservation(String user, Itinerary itinerary, int paid, int id) {
      this.username = user;
      this.itinerary = itinerary;
      this.paid = paid;
      this.id = id;
    }

    @Override
    public String toString() {
      String out = "Reservation " + this.id + " paid: " + (this.paid == 1) + ":\n" + this.itinerary.flight1;
      if (!this.itinerary.isDirect) {
        out += "\n" + this.itinerary.flight2;
      }
      return out;
    }
  }

}
