package com.cyk.bean;

/**
     * Content part for multimodal messages.
     */
    public class ContentPart {
        private String type;
        private String text;
        private ImageUrl imageUrl;

        public ContentPart() {}

        public static ContentPart text(String text) {
            ContentPart part = new ContentPart();
            part.type = "text";
            part.text = text;
            return part;
        }

        public static ContentPart image(String url) {
            ContentPart part = new ContentPart();
            part.type = "image_url";
            part.imageUrl = new ImageUrl(url);
            return part;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public ImageUrl getImageUrl() { return imageUrl; }
        public void setImageUrl(ImageUrl imageUrl) { this.imageUrl = imageUrl; }
    }