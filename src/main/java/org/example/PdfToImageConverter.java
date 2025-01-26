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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

@SpringBootApplication
@RestController
@RequestMapping("/api")
public class PdfToImageConverter {

    public static void main(String[] args) {
        SpringApplication.run(PdfToImageConverter.class, args);
        processImagesForOCR();
    }

    @PostMapping("/upload")
    public String uploadPdf(@RequestParam("pdfFile") MultipartFile file) {
        try {
            File pdfFile = convertMultiPartToFile(file);
            convertPdfToImage(pdfFile);
            processImagesForOCR();
            return "Processing complete. Check uploads directory for images and output.docx for results.";
        } catch (Exception e) {
            e.printStackTrace();
            return "Error processing PDF: " + e.getMessage();
        }
    }

    private File convertMultiPartToFile(MultipartFile file) throws IOException {
        File tempFile = File.createTempFile("temp", file.getOriginalFilename());
        file.transferTo(tempFile);
        return tempFile;
    }

    private void convertPdfToImage(File pdfFile) throws IOException {
        PDDocument document = PDDocument.load(pdfFile);
        PDFRenderer pdfRenderer = new PDFRenderer(document);
        File uploadsDir = new File("uploads");

        // Create the directory if it doesn't exist
        if (!uploadsDir.exists()) {
            uploadsDir.mkdir();
        }

        for (int page = 0; page < document.getNumberOfPages(); ++page) {
            BufferedImage bim = pdfRenderer.renderImageWithDPI(page, 300);
            String imagePath = "uploads/page-" + (page + 1) + ".jpg";
            ImageIO.write(bim, "jpg", new File(imagePath));
        }

        document.close();
    }

    private static void processImagesForOCR() {
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
                try (FileOutputStream out = new FileOutputStream("output.docx")) {
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