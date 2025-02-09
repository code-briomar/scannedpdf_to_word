package org.example;

import com.google.gson.JsonObject;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.coyote.Response;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;


import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootApplication
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "https://scanned-pdf-to-word.lomogan.africa/") // Allow frontend to access API
public class PdfToImageConverter {

    private static final ExecutorService executor = Executors.newFixedThreadPool(10);

    public static void main(String[] args) {
        SpringApplication.run(PdfToImageConverter.class, args);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> uploadPdf(@RequestParam("pdfFile") MultipartFile file) {
        try {
            String fileID = String.valueOf(System.currentTimeMillis());
            File pdfFile = convertMultiPartToFile(file);

            // Run async processing
//            CompletableFuture.runAsync(()->{
//                try{
//
//                }catch(Exception e){
//                    //TODO:Custom error message
//                    e.printStackTrace();
//                }
//            },executor);

            convertPdfToImage(pdfFile,fileID);


            // Immediate Response
            Map<String,Object> response = new HashMap<>();
            response.put("status","success");
            response.put("code",200);
            response.put("message","Processing started.");

            // When Data Exists
            Map<String,Object> data = new HashMap<>();
            data.put("fileID",fileID);

            response.put("data", data);

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

    //Check if file is ready
    @GetMapping("/check-status")
    public ResponseEntity<Object> checkFileStatus(@RequestParam("fileID") String fileID){
        File outputFile = new File("output_" + fileID + ".docx");


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

        //TODO::Temporary File Created During Processing ( with fileID as the name)
        //TODO::and deleted after processing. To help differentiate processing files and
        //TODO::none existent files
            //Processing
            Map<String,Object> response = new HashMap<>();
            response.put("status","success");
            response.put("code",200);
            response.put("message","file is still being processed. please wait");

            return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping("/download")
    public ResponseEntity<Object> downloadFile(@RequestParam("fileID") String fileID) throws IOException {
        File outputFile = new File("output_" + fileID + ".docx");

        if (!outputFile.exists()) {
            Map<String,Object> errorResponse = new HashMap<>();
            errorResponse.put("status","error");
            errorResponse.put("code",200);
            errorResponse.put("message","file does not exist.");
            errorResponse.put("data","null");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        }

        byte[] fileBytes = java.nio.file.Files.readAllBytes(outputFile.toPath());

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" +outputFile.getName())
                .body(fileBytes);
    }


    @GetMapping("/health")
    public ResponseEntity health(){
        Map<String,Object> response = new HashMap<>();
        response.put("status","success");
        response.put("code",200);
        response.put("message","scanned-pdf-to-word api is up. Make requests to /api/upload");
        response.put("data",null);

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    private File convertMultiPartToFile(MultipartFile file) throws IOException {
        File tempFile = File.createTempFile("temp", file.getOriginalFilename());
        file.transferTo(tempFile);
        return tempFile;
    }

    private void convertPdfToImage(File pdfFile, String fileID) throws IOException {
        PDDocument document = PDDocument.load(pdfFile);
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        File uploadsDir = new File("uploads");

        // Create the directory if it doesn't exist
        if (!uploadsDir.exists()) {
            uploadsDir.mkdir();
        }

         int pagesToBeProcessed = Math.min(document.getNumberOfPages(),5); // Limit to 30 on a free tier of some sorts.


        for (int page = 0; page < pagesToBeProcessed; ++page) {
            BufferedImage bim = pdfRenderer.renderImageWithDPI(page, 300);
            String imagePath = "uploads/page-" + (page + 1) + ".jpg";
            ImageIO.write(bim, "jpg", new File(imagePath));
        }


        document.close();

        processImagesForOCR(fileID);
    }

    private static void processImagesForOCR(String fileID) {
        File uploadsDir = new File("uploads");
        File[] files = uploadsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg"));

        if (files != null && files.length > 0) {
            ITesseract tesseract = new Tesseract();
            // Set the correct path to the tessdata folder
            tesseract.setDatapath(System.getProperty("user.dir") + File.separator + "tessdata");
            tesseract.setPageSegMode(1); // PSM_AUTO for layout analysis
            tesseract.setOcrEngineMode(1); // Set OCR mode to LSTM
            //tesseract.setConfigs(Arrays.asList("hocr")); // Generate hOCR output

            try (XWPFDocument document = new XWPFDocument()) {
                for (File imageFile : files) {
                    String result = tesseract.doOCR(imageFile);

                    if (result.isEmpty()) {
                        System.out.println("OCR returned no text for " + imageFile.getName());
                    } else {
                        // Split the result into lines to analyze formatting
                        String[] lines = result.split("\n");
                        for (String line : lines) {
                            XWPFParagraph paragraph = document.createParagraph();
                            XWPFRun run = paragraph.createRun();

                            // Apply formatting based on simple heuristics
                            if (line.trim().isEmpty()) {
                                continue; // Skip empty lines
                            } else if (line.matches("(?i).*(\\b[A-Z]{2,}\\b).*")) {
                                //run.setBold(true); // Set bold for potential headings
                            }

                            // Add text to the run
                            run.setText(line.trim());
                            run.setFontSize(12); // Set a default font size
                            paragraph.setAlignment(ParagraphAlignment.LEFT); // Set alignment
                        }
                        System.out.println("Processed image: " + imageFile.getName());
                    }
                }

                // Save the Word document
                File outputFile = new File("output_"+fileID+".docx");
                try (FileOutputStream out = new FileOutputStream(outputFile)) {
                    document.write(out);
                }

                // Delete the images after processing
                for (File imageFile : files) {
                    if (imageFile.delete()) {
                        System.out.println("Deleted image: " + imageFile.getName());
                    } else {
                        System.err.println("Failed to delete image: " + imageFile.getName());
                    }
                }
            } catch (TesseractException | IOException e) {
                System.err.println("Error during OCR processing: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("No images found for OCR processing.");
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<String> handleMaxSizeException(MaxUploadSizeExceededException exc) {
        System.err.println("File upload error: " + exc.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body("File size exceeds limit!");
    }
}