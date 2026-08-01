package com.electrahub.paymentgateway.service.twoc2p;

import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Strict JOSE and XML boundary for 2C2P Payment Action v4.3. */
final class TwoC2PMaintenanceCodec {

    static final int MAX_TOKEN_LENGTH = 1024 * 1024;
    static final int MAX_XML_LENGTH = 256 * 1024;

    private final ObjectMapper objectMapper;
    private final Clock clock;

    TwoC2PMaintenanceCodec(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
    }

    TwoC2PMaintenanceCodec(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    KeyMaterial parseKeyMaterial(String secretJson) {
        try {
            JsonNode value = objectMapper.readTree(secretJson);
            if (value == null || !value.isObject()) {
                throw invalidKeyMaterial();
            }
            String privatePem = requiredText(value, "merchantPrivateKeyPem");
            String certificatePem = requiredText(value, "twoC2PPublicCertificatePem");

            byte[] privateDer = decodePem(privatePem, "PRIVATE KEY");
            RSAPrivateKey merchantPrivateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(privateDer));
            X509Certificate providerCertificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certificatePem.getBytes(StandardCharsets.US_ASCII)));
            providerCertificate.checkValidity(Date.from(clock.instant()));
            if (!(providerCertificate.getPublicKey() instanceof RSAPublicKey providerPublicKey)
                    || merchantPrivateKey.getModulus().bitLength() < 2048
                    || providerPublicKey.getModulus().bitLength() < 2048) {
                throw invalidKeyMaterial();
            }
            return new KeyMaterial(merchantPrivateKey, providerPublicKey);
        } catch (GatewayBusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidKeyMaterial();
        }
    }

    String encodeRequest(MaintenanceRequest request, KeyMaterial keys) {
        return encryptThenSign(writeRequestXml(request), keys.providerPublicKey(), keys.merchantPrivateKey());
    }

    MaintenanceResponse decodeResponse(String token, KeyMaterial keys) {
        String xml = verifyAndDecrypt(token, keys.providerPublicKey(), keys.merchantPrivateKey());
        return parseResponseXml(xml);
    }

    static String encryptThenSign(String plaintext, RSAPublicKey encryptionKey, RSAPrivateKey signingKey) {
        if (plaintext == null || plaintext.length() > MAX_XML_LENGTH) {
            throw invalidResponse();
        }
        try {
            JWEObject jwe = new JWEObject(
                    new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP, EncryptionMethod.A256GCM).build(),
                    new Payload(plaintext)
            );
            jwe.encrypt(new RSAEncrypter(encryptionKey));
            JWSObject jws = new JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.PS256).type(JOSEObjectType.JWT).build(),
                    new Payload(jwe.serialize())
            );
            jws.sign(new RSASSASigner(signingKey));
            return jws.serialize();
        } catch (JOSEException exception) {
            throw new GatewayBusinessException(
                    "TWO_C2P_MAINTENANCE_REQUEST_INVALID",
                    "Unable to protect the 2C2P maintenance request."
            );
        }
    }

    static String verifyAndDecrypt(String token, RSAPublicKey verificationKey, RSAPrivateKey decryptionKey) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw invalidResponse();
        }
        try {
            JWSObject jws = JWSObject.parse(token.trim());
            if (!JWSAlgorithm.PS256.equals(jws.getHeader().getAlgorithm())
                    || !jws.verify(new RSASSAVerifier(verificationKey))) {
                throw invalidSignature();
            }
            JWEObject jwe = JWEObject.parse(jws.getPayload().toString());
            if (!JWEAlgorithm.RSA_OAEP.equals(jwe.getHeader().getAlgorithm())
                    || !EncryptionMethod.A256GCM.equals(jwe.getHeader().getEncryptionMethod())) {
                throw invalidResponse();
            }
            jwe.decrypt(new RSADecrypter(decryptionKey));
            String plaintext = jwe.getPayload().toString();
            if (plaintext.length() > MAX_XML_LENGTH) {
                throw invalidResponse();
            }
            return plaintext;
        } catch (GatewayBusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    String writeRequestXml(MaintenanceRequest request) {
        try {
            Document document = secureDocumentBuilder().newDocument();
            Element root = document.createElement("PaymentProcessRequest");
            document.appendChild(root);
            element(document, root, "version", "4.3");
            element(document, root, "merchantID", request.merchantId());
            element(document, root, "processType", request.processType());
            element(document, root, "invoiceNo", request.invoiceNo());
            element(document, root, "actionAmount", request.actionAmount().toPlainString());
            if (request.notifyUrl() != null) {
                element(document, root, "notifyURL", request.notifyUrl());
            }
            if (request.idempotencyId() != null) {
                element(document, root, "idempotencyID", request.idempotencyId());
            }

            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toString();
        } catch (Exception exception) {
            throw new GatewayBusinessException(
                    "TWO_C2P_MAINTENANCE_REQUEST_INVALID",
                    "Unable to create the 2C2P maintenance request."
            );
        }
    }

    MaintenanceResponse parseResponseXml(String xml) {
        if (xml == null || xml.isBlank() || xml.length() > MAX_XML_LENGTH) {
            throw invalidResponse();
        }
        try {
            Document document = secureDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            if (root == null || !"PaymentProcessResponse".equals(root.getTagName())) {
                throw invalidResponse();
            }
            List<Refund> refunds = new ArrayList<>();
            NodeList refundNodes = root.getElementsByTagName("refund");
            for (int index = 0; index < refundNodes.getLength(); index++) {
                Element refund = (Element) refundNodes.item(index);
                refunds.add(new Refund(
                        attribute(refund, "referenceNo"),
                        attribute(refund, "status"),
                        decimal(attribute(refund, "amount"))
                ));
            }
            return new MaintenanceResponse(
                    childText(root, "merchantID"),
                    childText(root, "respCode"),
                    childText(root, "respDesc"),
                    childText(root, "processType"),
                    childText(root, "invoiceNo"),
                    decimal(childText(root, "amount")),
                    childText(root, "status"),
                    firstPresent(childText(root, "refundReferenceNo"), childText(root, "referenceNo")),
                    childText(root, "referenceNo"),
                    childText(root, "idempotencyID"),
                    refunds
            );
        } catch (GatewayBusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        return factory;
    }

    private static DocumentBuilder secureDocumentBuilder() throws Exception {
        DocumentBuilder builder = secureDocumentBuilderFactory().newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(SAXParseException exception) throws SAXParseException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXParseException {
                throw exception;
            }
        });
        return builder;
    }

    private static void element(Document document, Element parent, String name, String value) {
        Element child = document.createElement(name);
        child.setTextContent(value);
        parent.appendChild(child);
    }

    private String requiredText(JsonNode object, String field) {
        String value = object.path(field).asText("").trim();
        if (value.isBlank()) {
            throw invalidKeyMaterial();
        }
        return value;
    }

    private static byte[] decodePem(String pem, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) {
            throw invalidKeyMaterial();
        }
        String body = pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String childText(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                String value = child.getTextContent();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }

    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal decimal(String value) {
        if (value == null) {
            return null;
        }
        if (!value.matches("^\\d{1,16}(?:\\.\\d{1,2})?$")) {
            throw invalidResponse();
        }
        return new BigDecimal(value);
    }

    private static String firstPresent(String first, String second) {
        return first == null ? second : first;
    }

    private static GatewayBusinessException invalidKeyMaterial() {
        return new GatewayBusinessException(
                "TWO_C2P_CERTIFICATE_INVALID",
                "The 2C2P certificate material is invalid or expired."
        );
    }

    private static GatewayBusinessException invalidSignature() {
        return new GatewayBusinessException(
                "TWO_C2P_MAINTENANCE_SIGNATURE_INVALID",
                "The 2C2P maintenance response signature is invalid."
        );
    }

    private static GatewayBusinessException invalidResponse() {
        return new GatewayBusinessException(
                "TWO_C2P_MAINTENANCE_RESPONSE_INVALID",
                "2C2P returned an invalid protected maintenance response."
        );
    }

    record KeyMaterial(RSAPrivateKey merchantPrivateKey, RSAPublicKey providerPublicKey) {
    }

    record MaintenanceRequest(
            String merchantId,
            String processType,
            String invoiceNo,
            BigDecimal actionAmount,
            String idempotencyId,
            String notifyUrl
    ) {
        MaintenanceRequest {
            processType = processType == null ? "" : processType.toUpperCase(Locale.ROOT);
        }
    }

    record MaintenanceResponse(
            String merchantId,
            String responseCode,
            String responseDescription,
            String processType,
            String invoiceNo,
            BigDecimal amount,
            String status,
            String refundReferenceNo,
            String referenceNo,
            String idempotencyId,
            List<Refund> refunds
    ) {
        MaintenanceResponse {
            refunds = refunds == null ? List.of() : List.copyOf(refunds);
        }
    }

    record Refund(String referenceNo, String status, BigDecimal amount) {
    }
}
