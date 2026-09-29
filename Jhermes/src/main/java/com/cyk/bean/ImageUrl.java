package com.cyk.bean;

/**
     * Image URL structure.
     */
    public class ImageUrl {
        private String url;
        private String detail;

        public ImageUrl() {}

        public ImageUrl(String url) {
            this.url = url;
            this.detail = "auto";
        }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
    }