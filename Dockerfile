# Use a lightweight base image for the runtime
FROM openjdk:20-slim

# Set the working directory inside the container
WORKDIR /app

# Install Tesseract OCR and its dependencies
RUN apt-get update && apt-get install -y tesseract-ocr libtesseract-dev

# Set the LD_LIBRARY_PATH to include the path to the Tesseract OCR libraries
ENV LD_LIBRARY_PATH=/usr/lib/tesseract:$LD_LIBRARY_PATH

# Add metadata about the application
LABEL maintainer="Briane Lomoni <kapolonbraine@gmail.com>"
LABEL version="1.0.0"
LABEL description="An application that converts scanned PDFs to Word documents."
LABEL app.name="Scanned PDF to Word Converter"
LABEL app.version="1.0.0"
LABEL app.license="MIT"

# Copy the JAR file into the container
COPY target/scannedpdf_to_word-1.0-SNAPSHOT.jar app.jar

# Copy the tessdata directory into the container
COPY tessdata/ tessdata/

# Expose the port the application runs on
EXPOSE 6969

# Define the command to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]