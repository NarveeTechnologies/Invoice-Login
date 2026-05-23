package com.example.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/public")
public class PublicController {

    @GetMapping("/features")
    public ResponseEntity<List<Map<String, Object>>> getFeatures() {
        List<Map<String, Object>> features = Arrays.asList(
            buildFeature(1, "AI Invoice Capture",
                "Automatically extract data from any invoice format using advanced OCR",
                "document_scanner"),
            buildFeature(2, "Smart Approval Workflows",
                "Route invoices through customizable multi-level approval chains",
                "account_tree"),
            buildFeature(3, "ERP Integration",
                "Seamlessly connect with QuickBooks, Xero, SAP, and 50+ ERPs",
                "integration_instructions"),
            buildFeature(4, "Fraud Detection",
                "AI-powered anomaly detection catches duplicate and fraudulent invoices",
                "security"),
            buildFeature(5, "Multi-Currency & Global Payments",
                "Process payments in 140+ currencies with real-time FX rates",
                "currency_exchange"),
            buildFeature(6, "Real-time Analytics",
                "Live dashboards tracking spend, approval rates, and cash flow",
                "analytics")
        );
        return ResponseEntity.ok(features);
    }

    @GetMapping("/pricing")
    public ResponseEntity<List<Map<String, Object>>> getPricing() {
        List<Map<String, Object>> plans = Arrays.asList(
            buildPlan(1, "Starter", "$39/month", "Per user/month",
                "Perfect for small businesses getting started with invoice automation",
                Arrays.asList(
                    "Up to 100 invoices/month",
                    "Email ingestion",
                    "Basic AI OCR",
                    "1 user account",
                    "Standard support",
                    "CSV export"
                ),
                false, "Start Free Trial", "/register"),
            buildPlan(2, "Growth", "$149/month", "Per user/month",
                "Full automation suite for growing teams",
                Arrays.asList(
                    "Unlimited invoices",
                    "Full AI invoice capture",
                    "Multi-level approval workflows",
                    "QuickBooks & Xero integration",
                    "Up to 10 users",
                    "Priority support",
                    "API access"
                ),
                true, "Get Started", "/register"),
            buildPlan(3, "Enterprise", "Custom", "Contact us",
                "Enterprise-grade for large organizations",
                Arrays.asList(
                    "Everything in Growth",
                    "Global payment processing",
                    "Advanced AI fraud detection",
                    "Unlimited users",
                    "Dedicated account manager",
                    "SLA guarantee",
                    "Custom integrations",
                    "SSO/SAML support"
                ),
                false, "Contact Sales", "/contact")
        );
        return ResponseEntity.ok(plans);
    }

    @GetMapping("/comparison")
    public ResponseEntity<Map<String, Object>> getComparison() {
        Map<String, Object> comparison = new HashMap<>();

        comparison.put("headers", Arrays.asList(
            "Feature", "InvoiceAI (Us)", "Tipalti", "Bill.com", "Coupa", "SAP Ariba", "Basware"
        ));

        List<Map<String, Object>> rows = Arrays.asList(
            buildRow("Starting Price",
                new String[]{"$39/mo", "$149/mo", "$45/mo", "Custom", "Custom", "Custom"}),
            buildRow("Mid-tier Price",
                new String[]{"$149/mo", "$399/mo", "$79/mo", "$2,000+/mo", "$5,000+/mo", "Custom"}),
            buildRow("Implementation Time",
                new String[]{"1 day", "4-8 weeks", "2-4 weeks", "3-6 months", "6-12 months", "3-6 months"}),
            buildRow("AI-Powered OCR",
                new String[]{"✓", "✓", "Limited", "✓", "✓", "✓"}),
            buildRow("ERP Integration",
                new String[]{"50+ ERPs", "200+ ERPs", "QuickBooks/Xero", "100+ ERPs", "SAP native", "50+ ERPs"}),
            buildRow("Fraud Detection",
                new String[]{"Advanced AI", "Basic", "Basic", "Advanced", "Advanced", "Basic"}),
            buildRow("Mobile App",
                new String[]{"✓", "✓", "✓", "✓", "Limited", "Limited"}),
            buildRow("Free Trial",
                new String[]{"14 days", "Demo only", "30 days", "Demo only", "Demo only", "Demo only"})
        );

        comparison.put("rows", rows);
        return ResponseEntity.ok(comparison);
    }

    // --- helpers ---

    private Map<String, Object> buildFeature(int id, String title, String description, String icon) {
        Map<String, Object> f = new HashMap<>();
        f.put("id", id);
        f.put("title", title);
        f.put("description", description);
        f.put("icon", icon);
        return f;
    }

    private Map<String, Object> buildPlan(int id, String name, String price, String priceNote,
                                          String description, List<String> features,
                                          boolean highlighted, String ctaText, String ctaLink) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("name", name);
        p.put("price", price);
        p.put("priceNote", priceNote);
        p.put("description", description);
        p.put("features", features);
        p.put("highlighted", highlighted);
        p.put("ctaText", ctaText);
        p.put("ctaLink", ctaLink);
        return p;
    }

    private Map<String, Object> buildRow(String feature, String[] values) {
        Map<String, Object> row = new HashMap<>();
        row.put("feature", feature);
        row.put("values", values);
        return row;
    }
}
