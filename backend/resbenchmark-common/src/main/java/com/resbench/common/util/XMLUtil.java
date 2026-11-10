package com.resbench.common.util;

import java.io.File;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.io.FileHandler;
import org.apache.commons.configuration2.tree.xpath.XPathExpressionEngine;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class XMLUtil {

    public static XMLConfiguration buildConfiguration(String filename) throws ConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(true);
        javax.xml.parsers.DocumentBuilder b = null;
        try {
            b = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
        XMLConfiguration xmlConfiguration = new XMLConfiguration();
        xmlConfiguration.setDocumentBuilder(b);
        xmlConfiguration.setExpressionEngine(new XPathExpressionEngine());
        FileHandler fileHandler = new FileHandler(xmlConfiguration);
        fileHandler.load(new File(filename));
        return xmlConfiguration;
    }

    public static String getTextContent(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? ((Element) nodes.item(0)).getTextContent() : "";
    }

    public static Document readXmlToDocument(String filePath) {
        try {
            File xmlFile = new File(filePath);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(xmlFile);
            doc.getDocumentElement().normalize();
            return doc;
        } catch (Exception e) {
            throw new RuntimeException("Load XML Failed", e);
        }
    }
}
