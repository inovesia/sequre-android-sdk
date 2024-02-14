package id.sequre.sample;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import id.sequre.sample.databinding.ResultBinding;
import id.sequre.sample.databinding.SampleBinding;
import id.sequre.sdk.Sequre;

public class Sample extends AppCompatActivity {
    private final static String APPLICATION_NUMBER = "1693974116";
    private SampleBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sample);

        binding = SampleBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        Sequre sequre = new Sequre();
        sequre.init(this, APPLICATION_NUMBER);
        sequre.setLanguage("id");

        binding.scan.setOnClickListener(view -> {
            sequre.scan(result -> {
                System.out.println(":: result: " + result);
                ResultBinding item = ResultBinding.inflate(getLayoutInflater());
                item.resultStatus.setText(String.format("%s", result.getStatus()));
                item.resultMessage.setText(result.getMessage() == null ? "" : result.getMessage());
                item.resultQr.setText(result.getQr() == null ? "" : result.getQr());
                item.resultScore.setText(result.getScore() == null ? "" : (String.format("%s", result.getScore())).substring(0, 4));
                item.resultTimeline.setText(result.getTimeline() == null ? "" : result.getTimeline());
                runOnUiThread(() -> binding.results.addView(item.getRoot(), 0));
            });
        });
    }
}