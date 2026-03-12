package com.train.booking.service;

import com.train.booking.domain.Reservation;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Generates a PDF ticket for a confirmed/paid reservation.
 */
@Service
@Slf4j
public class TicketPdfService {

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm").withZone(ZoneId.systemDefault());

    /**
     * Generate PDF bytes for the given reservation (must have tripSeat, trip, seat, train loaded).
     */
    public byte[] generateTicketPdf(Reservation r) {
        if (r == null || r.getTripSeat() == null) {
            throw new IllegalArgumentException("Reservation or trip seat missing");
        }
        var ts = r.getTripSeat();
        var trip = ts.getTrip();
        var seat = ts.getSeat();
        var train = trip != null ? trip.getTrain() : null;

        String fromStation = trip != null ? trip.getFromStation() : "—";
        String toStation = trip != null ? trip.getToStation() : "—";
        String departureStr = trip != null && trip.getDepartureTime() != null
            ? FORMATTER.format(trip.getDepartureTime()) : "—";
        String seatNumber = seat != null ? seat.getSeatNumber() : "—";
        String trainName = train != null ? train.getName() : "—";
        String trainCode = train != null ? train.getCode() : "—";
        String amount = r.getAmount() != null ? r.getAmount().toPlainString() : "—";
        String currency = r.getCurrency() != null ? r.getCurrency() : "GBP";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A5);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            Font headingFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 11);

            document.add(new Paragraph("Train Ticket", titleFont));
            document.add(Chunk.NEWLINE);
            document.add(new Paragraph("Reservation ID: " + r.getId(), headingFont));
            document.add(new Paragraph("Ticket reference: " + (r.getPaymentReference() != null ? r.getPaymentReference() : r.getId().toString()), normalFont));
            document.add(Chunk.NEWLINE);
            document.add(new Paragraph("Route", headingFont));
            document.add(new Paragraph(fromStation + " → " + toStation, normalFont));
            document.add(new Paragraph("Departure: " + departureStr, normalFont));
            document.add(Chunk.NEWLINE);
            document.add(new Paragraph("Train: " + trainName + " (" + trainCode + ")", normalFont));
            document.add(new Paragraph("Seat: " + seatNumber, normalFont));
            document.add(Chunk.NEWLINE);
            document.add(new Paragraph("Amount paid: " + amount + " " + currency, normalFont));
            document.add(new Paragraph("Status: " + r.getStatus().name(), normalFont));
            document.add(Chunk.NEWLINE);
            // QR code encoding reservation ID (same as app ticket QR for validation)
            byte[] qrPng = generateQrPng(String.valueOf(r.getId()), 120, 120);
            if (qrPng != null && qrPng.length > 0) {
                try {
                    Image qrImage = Image.getInstance(qrPng);
                    qrImage.setAlignment(Element.ALIGN_CENTER);
                    document.add(qrImage);
                    document.add(Chunk.NEWLINE);
                } catch (Exception e) {
                    log.debug("Could not embed QR in PDF", e);
                }
            }
            document.add(new Paragraph("Present this ticket (or its QR code) when boarding. Valid for travel on the date shown.", normalFont));
            document.close();
        } catch (DocumentException e) {
            log.warn("Failed to generate ticket PDF", e);
            throw new RuntimeException("Failed to generate ticket PDF", e);
        }
        return out.toByteArray();
    }

    /** Generate QR code as PNG bytes (content = reservation ID for validation). */
    private byte[] generateQrPng(String content, int width, int height) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", png);
            return png.toByteArray();
        } catch (Exception e) {
            log.warn("Failed to generate QR code", e);
            return null;
        }
    }
}
