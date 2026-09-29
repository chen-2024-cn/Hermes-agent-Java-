package com.cyk.bean;

import java.util.ArrayList;
import java.util.List;

    public class ExtractionResult {
        private List<String> insights = new ArrayList<>();
        private List<String> memoriesSaved = new ArrayList<>();
        private Skill skillCandidate;
        
        public static ExtractionResult empty() {
            return new ExtractionResult();
        }
        
        public List<String> getInsights() { return insights; }
        public void setInsights(List<String> insights) { this.insights = insights; }
        
        public List<String> getMemoriesSaved() { return memoriesSaved; }
        public void addMemorySaved(String memory) { this.memoriesSaved.add(memory); }
        
        public Skill getSkillCandidate() { return skillCandidate; }
        public void setSkillCandidate(Skill skillCandidate) { this.skillCandidate = skillCandidate; }
        
        public boolean hasSkillCandidate() {
            return skillCandidate != null;
        }
    }