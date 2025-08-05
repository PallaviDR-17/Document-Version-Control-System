package com.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.*;
import java.util.Scanner;
import java.nio.charset.StandardCharsets; // Although for binary files, we'll mostly use byte[]

public class VersionControlSystem {

    private static final String URL = "jdbc:postgresql://localhost:5432/testdb";
    private static final String USER = "postgres";
    private static final String PASSWORD = "Reddappa@24";

    // This method will handle both initial upload and versioning of existing files
    public void uploadOrVersionDocument(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("Error: The file does not exist at " + filePath);
            return;
        }

        String filename = file.getName();
        Path path = file.toPath(); // Use java.nio.file.Path for modern file operations
        byte[] fileData;
        String currentFileChecksum;
        FileTime lastModifiedTime;

        try {
            fileData = Files.readAllBytes(path);
            currentFileChecksum = calculateChecksum(fileData);
            lastModifiedTime = Files.getLastModifiedTime(path);
        } catch (IOException e) {
            System.err.println("Error reading file or getting last modified time: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            // 1. Check if the document (by filename) already exists and get its latest version details
            String selectLatestVersionQuery = "SELECT version, checksum FROM documents WHERE filename = ? ORDER BY version DESC LIMIT 1";
            PreparedStatement selectStmt = conn.prepareStatement(selectLatestVersionQuery);
            selectStmt.setString(1, filename);
            ResultSet rs = selectStmt.executeQuery();

            int latestDbVersion = 0;
            String latestDbChecksum = null;
            boolean documentExistsInDb = false;

            if (rs.next()) {
                documentExistsInDb = true;
                latestDbVersion = rs.getInt("version");
                latestDbChecksum = rs.getString("checksum");
            }
            rs.close();
            selectStmt.close();

            // 2. Decide if it's a new upload or a new version
            if (!documentExistsInDb) {
                // Scenario 1: First upload of this document
                insertNewVersion(conn, 1, "Initial upload", filename, fileData, currentFileChecksum, lastModifiedTime);
                System.out.println("File '" + filename + "' uploaded successfully as Version 1.");
            } else if (currentFileChecksum.equals(latestDbChecksum)) {
                // Scenario 2: Document exists, but content is unchanged
                System.out.println("No changes detected for '" + filename + "'. Current version: " + latestDbVersion);
            } else {
                // Scenario 3: Document exists and content has changed (new version)
                int newVersion = latestDbVersion + 1;
                insertNewVersion(conn, newVersion, "Content modified", filename, fileData, currentFileChecksum, lastModifiedTime);
                System.out.println("Changes detected for '" + filename + "'. New version " + newVersion + " added.");
            }

        } catch (SQLException e) {
            System.err.println("Database error during document upload/versioning: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Helper method to insert a new version into the database
    private void insertNewVersion(Connection conn, int version, String changeDescription, String filename, byte[] fileData, String checksum, FileTime lastModifiedTime) throws SQLException {
        String insertQuery = "INSERT INTO documents (version, content, filename, file_data, checksum, last_modified, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        PreparedStatement pstmt = conn.prepareStatement(insertQuery);

        pstmt.setInt(1, version);
        // Using StandardCharsets.UTF_8 for content if it's a text file. For binary, content might be empty or a placeholder.
        // If content is just a placeholder, you might make the 'content' column nullable in DB.
        pstmt.setString(2, "Content of version " + version); // Or new String(fileData, StandardCharsets.UTF_8) if text
        pstmt.setString(3, filename);
        pstmt.setBytes(4, fileData);
        pstmt.setString(5, checksum);
        pstmt.setTimestamp(6, new Timestamp(lastModifiedTime.toMillis()));
        pstmt.setTimestamp(7, new Timestamp(System.currentTimeMillis())); // Current time of upload

        pstmt.executeUpdate();
        pstmt.close();
    }


    public void viewDocumentVersions(String docName) { // Simplified: directory not strictly needed for viewing
        String selectQuery = "SELECT version, checksum, last_modified, uploaded_at FROM documents WHERE filename = ? ORDER BY version ASC";

        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             PreparedStatement selectStmt = conn.prepareStatement(selectQuery)) {

            selectStmt.setString(1, docName);
            ResultSet rs = selectStmt.executeQuery();

            boolean found = false;
            System.out.println("Versions for document: " + docName);
            while (rs.next()) {
                found = true;
                int version = rs.getInt("version");
                String checksum = rs.getString("checksum");
                Timestamp lastModifiedDb = rs.getTimestamp("last_modified");
                Timestamp uploadedAt = rs.getTimestamp("uploaded_at");

                System.out.println("  - Version: " + version +
                        ", Checksum: " + checksum.substring(0, Math.min(checksum.length(), 10)) + "..." +
                        ", Last Modified (File): " + lastModifiedDb +
                        ", Uploaded/Versioned At: " + uploadedAt);
            }

            if (!found) {
                System.out.println("No versions found for the document: " + docName + ". Please upload it first.");
            }

        } catch (SQLException e) {
            System.err.println("Error while retrieving document versions: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Method to calculate checksum of file data (already correct)
    private String calculateChecksum(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Error calculating checksum", e);
        }
    }

    public static void main(String[] args) {
        VersionControlSystem vcs = new VersionControlSystem();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("\nChoose an option:");
            System.out.println("1. Upload/Version a document");
            System.out.println("2. View versions of a document");
            System.out.println("3. Exit");
            System.out.print("Enter your choice: ");

            int choice = scanner.nextInt();
            scanner.nextLine(); // Consume newline

            if (choice == 1) {
                System.out.print("Enter full file path (e.g., C:/Users/YourUser/Documents/myfile.txt) to upload/version: ");
                String filePath = scanner.nextLine();
                vcs.uploadOrVersionDocument(filePath);
            } else if (choice == 2) {
                System.out.print("Enter the document name (e.g., myfile.txt) to view its versions: ");
                String docName = scanner.nextLine();
                // The directory is not needed for viewing, as all versions are linked by filename in the DB
                vcs.viewDocumentVersions(docName);
            } else if (choice == 3) {
                System.out.println("Exiting the system.");
                break;
            } else {
                System.out.println("Invalid choice. Please try again.");
            }
        }

        scanner.close();
    }
}