package org.example;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;


import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
@EnableAsync
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "https://scanned-pdf-to-word.lomogan.africa/") // Allow frontend to access API
public class PdfToImageConverter {
    // Store the progress for each fileID
    private static final Map<String,Integer> progressMap = Collections.synchronizedMap(new HashMap<>());

    public static void main(String[] args) {
        SpringApplication.run(PdfToImageConverter.class, args);
    }

    /**
     * Uploads a PDF file and starts the processing (PDF to Image + OCR).
     * The processing runs asynchronously.
     *
     * @param file The PDF file uploaded as multipart/form-data.
     * @return ResponseEntity with a success message and file ID or an error message.
     *
     * Example Response (Success):
     * {
     *   "status": "success",
     *   "code": 200,
     *   "message": "Processing started.",
     *   "data": {
     *      "fileID": "a1b2c3d4-e5f6-7890-1234-56789abcdef0"
     *   }
     * }
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String,Object>> uploadPdf(@RequestParam("pdfFile") MultipartFile file) {
        try {
            // Remove all files ending with .tmp in the root folder
            Files.list(Paths.get("."))
                 .filter(path -> path.toString().endsWith(".tmp"))
                 .forEach(path -> {
                     try {
                         Files.delete(path);
                     } catch (IOException e) {
                         e.printStackTrace();
                     }
                 });

            String fileID = UUID.randomUUID().toString();
            File pdfFile = convertMultiPartToFile(file);

            new Thread(() -> {
                try {
                    convertPdfToImage(pdfFile,fileID);
                    processImagesForOCR(fileID);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).start();


            // Immediate Response
            Map<String,Object> response = new HashMap<>();
            response.put("status","success");
            response.put("code",200);
            response.put("message","Processing started.");

            // When Data Exists
            Map<String,Object> data = new HashMap<>();
            data.put("fileID",fileID);

            response.put("data", data);

            // Create a temporary file for tracking
            try{
            File tempProcessingFile = new File(fileID+".tmp");

            if(tempProcessingFile.createNewFile()){
                System.out.println("Temporary Tracking File Created");
            } else {
                System.out.println("Temporary Tracking File Not Created");
            }
            }catch(IOException e){
                System.out.println("Temporary File Not Created");
            }


            return ResponseEntity.status(HttpStatus.OK).body(response);
        } catch (Exception e) {
            e.printStackTrace();

            // Error response
            Map<String,Object> errorResponse = new HashMap<>();
            errorResponse.put("status","error");
            errorResponse.put("code",500);
            errorResponse.put("message","an error occurred. please try again");
            errorResponse.put("data",null);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }


    /**
     * Checks the status of a file processing request.
     * <p>
     * This method verifies whether a processed Word document (`.docx`) corresponding to the provided
     * {@code fileID} exists in the system. If the file exists, a download URL is returned.
     * If not, the response indicates that the file is still being processed.
     * </p>
     *
     * @param fileID The unique identifier of the file being processed.
     * @return A {@link ResponseEntity} containing the file status information.
     *         <ul>
     *           <li>If the file is ready: returns HTTP 200 with a download URL.</li>
     *           <li>If the file is still processing: returns HTTP 200 with a wait message.</li>
     *         </ul>
     */
    @GetMapping("/check-status")
    public ResponseEntity<Object> checkFileStatus(@RequestParam("fileID") String fileID){
        File outputFile = new File("output/output_" + fileID + ".docx");


        if(outputFile.exists()){
            Map<String,Object> response = new HashMap<>();
            response.put("status","success");
            response.put("code",200);
            response.put("message","file is ready for download");
            //data exists
            Map<String,Object> data = new HashMap<>();
            data.put("download_url","/download?fileID="+fileID);
            response.put("data",data);
            return ResponseEntity.status(HttpStatus.OK).body(response);
        }

        //Check if temp file exists
        File tempProcessingFile = new File(fileID+".tmp");
        if(tempProcessingFile.exists()){
            //Processing
            Map<String,Object> response = new HashMap<>();
            response.put("status","success");
            response.put("code",200);
            response.put("message","file is still being processed. please wait");

            return ResponseEntity.status(HttpStatus.OK).body(response);
        } else if(!tempProcessingFile.exists()) {
            //File Does Not Exist
            Map<String,Object> response = new HashMap<>();
            response.put("status","error");
            response.put("code",404);
            response.put("message","file does not exist. upload a pdf file to get it processed.");

            return ResponseEntity.status(HttpStatus.OK).body(response);
        }

        //Error occurred
        Map<String,Object> response = new HashMap<>();
        response.put("status","error");
        response.put("code",500);
        response.put("message","an issue occurred during processing. try again");

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Handles file download requests.
     * <p>
     * This endpoint allows users to download a processed Word document (`.docx`) based on the given
     * {@code fileID}. If the file exists, it returns the file as a byte array with appropriate
     * headers to trigger a download. If the file does not exist, an error response is returned.
     * </p>
     *
     * @param fileID The unique identifier of the file being requested.
     * @return A {@link ResponseEntity} containing the file data or an error message.
     *         <ul>
     *           <li>If the file exists: returns HTTP 200 with file data and download headers.</li>
     *           <li>If the file does not exist: returns HTTP 404 with an error message.</li>
     *         </ul>
     * @throws IOException If an error occurs while reading the file.
     */
    @GetMapping("/download")
    public ResponseEntity<Object> downloadFile(@RequestParam("fileID") String fileID) throws IOException {
        File outputFile = new File("output/output_" + fileID + ".docx");
        File temporaryFile = new File(fileID+".tmp");
        if (!outputFile.exists() || !temporaryFile.exists()) {
            Map<String,Object> errorResponse = new HashMap<>();
            errorResponse.put("status","error");
            errorResponse.put("code",404);
            errorResponse.put("message","file does not exist.");
            errorResponse.put("data","null");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }


        byte[] fileBytes = Files.readAllBytes(outputFile.toPath());

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" +outputFile.getName())
                .body(fileBytes);
    }

    /**
     * Health check endpoint for the Scanned PDF to Word API.
     *
     * @return ResponseEntity containing a status message indicating that the API is running.
     *
     * Response format:
     * {
     *   "status": "success",
     *   "code": 200,
     *   "message": "scanned-pdf-to-word api is up. Make requests to /api/upload",
     *   "data": null
     * }
     */
    @GetMapping("/health")
    public ResponseEntity health(){
        Map<String,Object> response = new HashMap<>();
        response.put("status","success");
        response.put("code",200);
        response.put("message","scanned-pdf-to-word api is up. Make requests to /api/upload");
        response.put("data",null);

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * Retrieves the progress of a file being processed.
     * <p>
     * This endpoint checks the current progress of a file identified by the given {@code fileID}.
     * The progress is measured from 0% (processing started) to 100% (processing completed).
     * If the file is not found or not being processed, an error response is returned.
     * </p>
     *
     * <p><strong>Example Request:</strong></p>
     * <pre>
     * GET /api/progress?fileID=1234
     * </pre>
     *
     * <p><strong>Example Responses:</strong></p>
     * <p><strong>Ongoing Processing:</strong></p>
     * <pre>
     * {
     *   "status": "success",
     *   "code": 200,
     *   "progress": 75,
     *   "message": "Processing progress: 75%"
     * }
     * </pre>
     *
     * <p><strong>Processing Complete:</strong></p>
     * <pre>
     * {
     *   "status": "success",
     *   "code": 200,
     *   "progress": 100,
     *   "message": "Processing progress: 100%"
     * }
     * </pre>
     *
     * <p><strong>File Not Found:</strong></p>
     * <pre>
     * {
     *   "status": "error",
     *   "code": 404,
     *   "message": "File not found or not being processed."
     * }
     * </pre>
     *
     * @param fileID The unique identifier of the file being processed.
     * @return A {@link ResponseEntity} containing:
     *         <ul>
     *           <li>If the file is found: HTTP 200 with progress percentage (0-100%).</li>
     *           <li>If the file is not found: HTTP 404 with an error message.</li>
     *         </ul>
     */
    @GetMapping("/progress")
    public ResponseEntity<Object> getProgress(@RequestParam("fileID") String fileID) {
        Integer progress = progressMap.getOrDefault(fileID, -1);

        Map<String, Object> response = new HashMap<>();
        if (progress == -1) {
            response.put("status", "error");
            response.put("code", 404);
            response.put("message", "File not found or not being processed.");
        } else {
            response.put("status", "success");
            response.put("code", 200);
            response.put("progress", progress);
            response.put("message", "Processing progress: " + progress + "%");
        }

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }



    /**
     * Converts a MultipartFile to a temporary File.
     *
     * <p>This method creates a temporary file with a prefix "temp" and
     * the original filename from the uploaded MultipartFile.
     * The file is then transferred to this temporary location.</p>
     *
     * @param file the MultipartFile to be converted
     * @return the converted temporary File
     * @throws IOException if an error occurs during file creation or transfer
     */
    private File convertMultiPartToFile(MultipartFile file) throws IOException {
        File tempFile = File.createTempFile("temp", file.getOriginalFilename());
        file.transferTo(tempFile);
        return tempFile;
    }

    /**
     * Processes images in the "uploads" directory using Tesseract OCR and saves the extracted text
     * into a Word document.
     * <p>
     * The method:
     * <ul>
     *   <li>Scans the "uploads" directory for JPEG images.</li>
     *   <li>Uses Tesseract OCR to extract text from each image.</li>
     *   <li>Formats and writes the extracted text into a Word document.</li>
     *   <li>Deletes the processed images after saving the document.</li>
     * </ul>
     *
     * @param fileID A unique identifier for the output document file name.
     */
    private void processImagesForOCR(String fileID) {
        File uploadsDir = new File("uploads");
        File[] files = uploadsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg"));

        if (files != null && files.length > 0) {
            ITesseract tesseract = new Tesseract();
            tesseract.setDatapath(System.getProperty("user.dir") + File.separator + "tessdata");
            tesseract.setPageSegMode(1);
            tesseract.setOcrEngineMode(1);

            try (XWPFDocument document = new XWPFDocument()) {
                int totalImages = files.length;
                for (int i = 0; i < totalImages; i++) {
                    File imageFile = files[i];
                    String result = tesseract.doOCR(imageFile);

                    if (!result.isEmpty()) {
                        XWPFParagraph paragraph = document.createParagraph();
                        XWPFRun run = paragraph.createRun();
                        run.setText(result.trim());
                        run.setFontSize(12);
                        paragraph.setAlignment(ParagraphAlignment.LEFT);
                    }

                    // Update progress (50% to 100%)
                    progressMap.put(fileID, 50 + (int) (((i + 1) / (float) totalImages) * 50));
                }

                File outputFile = new File("output/output_" + fileID + ".docx");
                try (FileOutputStream out = new FileOutputStream(outputFile)) {
                    document.write(out);
                }

                // Delete images after processing
                for (File imageFile : files) {
                    imageFile.delete();
                }

                progressMap.put(fileID, 100); // Processing complete
            } catch (TesseractException | IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Converts a PDF file into images and saves them as JPEGs.
     *
     * <p>This method loads a PDF file, renders its pages as images, and saves
     * them in the "uploads" directory. It processes up to a maximum of 5 pages,
     * but this limit can be adjusted for different tiers.</p>
     *
     * @param pdfFile the PDF file to be converted into images
     * @throws IOException if an error occurs while reading the PDF or saving the images
     */
    private  void convertPdfToImage(File pdfFile,String fileID) throws IOException {
        PDDocument document = PDDocument.load(pdfFile);
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        File uploadsDir = new File("uploads");

        // Create the directory if it doesn't exist
        if (!uploadsDir.exists()) {
            uploadsDir.mkdir();
        }

        int pagesToBeProcessed = Math.min(document.getNumberOfPages(),5); // Limit to 30 on a free tier of some sorts.

        progressMap.put(fileID,0);


        for (int page = 0; page < pagesToBeProcessed; ++page) {
            BufferedImage bim = pdfRenderer.renderImageWithDPI(page, 300);
            String imagePath = "uploads/page-" + (page + 1) + ".jpg";
            ImageIO.write(bim, "jpg", new File(imagePath));

            // Update progress ( e.g 20% for 5 pages....Something like that )
            progressMap.put(fileID, (int) (((page+1)/(float)pagesToBeProcessed)*50));
        }

        document.close();
    }


    /**
     * Handles exceptions when a user uploads a file that exceeds the maximum allowed size.
     *
     * @param exc the exception thrown when the file size limit is exceeded.
     * @return a {@link ResponseEntity} with HTTP status 413 (Payload Too Large) and a message indicating the issue.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleMaxSizeException(MaxUploadSizeExceededException exc) {
        System.err.println("File upload error: " + exc.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body("File size exceeds limit!");
    }
}