/*
 * Copyright [2013-2014] eBay Software Foundation
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.shifu.shifu.core.dtrain;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import ml.shifu.guagua.master.MasterComputable;
import ml.shifu.guagua.master.MasterContext;
import ml.shifu.shifu.container.obj.ColumnConfig;
import ml.shifu.shifu.container.obj.ModelConfig;
import ml.shifu.shifu.container.obj.RawSourceData.SourceType;
import ml.shifu.shifu.util.CommonUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LogisticRegressionMaster} defines logic to update global <a
 * href=http://en.wikipedia.org/wiki/Logistic_regression >logistic regression</a> model.
 * 
 * <p>
 * At first iteration, master builds a random model then send to all workers to start computing. This is to make all
 * workers use the same model at the starting time.
 * 
 * <p>
 * At other iterations, master works:
 * <ul>
 * <li>1. Accumulate all gradients from workers.</li>
 * <li>2. Update global models by using accumulated gradients.</li>
 * <li>3. Send new global model to workers by returning model parameters.</li>
 * </ul>
 */
public class LogisticRegressionMaster implements MasterComputable<LogisticRegressionParams, LogisticRegressionParams> {

    private static final Logger LOG = LoggerFactory.getLogger(LogisticRegressionMaster.class);

    private static final Random RANDOM = new Random();

    private int inputNum;

    private double[] weights;

    private double learningRate = 1.0d;

    private double regularizedConstant = 0.0d;

    /**
     * Model configuration loaded from configuration file.
     */
    private ModelConfig modelConfig;

    /**
     * Column Config list read from HDFS
     */
    private List<ColumnConfig> columnConfigList;

    private void init(MasterContext<LogisticRegressionParams, LogisticRegressionParams> context) {
        loadConfigFiles(context.getProps());
        this.learningRate = Double.valueOf(this.modelConfig.getParams()
                .get(LogisticRegressionContants.LR_LEARNING_RATE).toString());
        this.regularizedConstant = Double.valueOf(this.modelConfig.getParams()
                .get(LogisticRegressionContants.LR_REGULARIZED_CONSTANT).toString());
        int[] inputOutputIndex = NNUtils.getInputOutputCandidateCounts(this.columnConfigList);
        this.inputNum = inputOutputIndex[0] == 0 ? inputOutputIndex[2] : inputOutputIndex[0];
    }

    @Override
    public LogisticRegressionParams compute(MasterContext<LogisticRegressionParams, LogisticRegressionParams> context) {
        if(context.isFirstIteration()) {
            init(context);
            weights = new double[this.inputNum];
            for(int i = 0; i < weights.length; i++) {
                weights[i] = RANDOM.nextDouble();
            }
            return new LogisticRegressionParams(weights);
        } else {
            double[] gradients = new double[this.inputNum];
            double trainError = 0.0d, testError = 0d;
            long recordCount = 0;
            for(LogisticRegressionParams param: context.getWorkerResults()) {
                if(param != null) {
                    for(int i = 0; i < gradients.length; i++) {
                        gradients[i] += param.getParameters()[i];
                    }
                    trainError += param.getTrainError();
                    testError += param.getTestError();
                    recordCount += param.getRecordCount();
                }
            }
            LOG.info("recordCount_master" + recordCount);
            for(int i = 0; i < weights.length; i++) {
                weights[i] -= learningRate * ((gradients[i] + this.regularizedConstant * weights[i]) / recordCount);
            }
            double reg = this.regularizedParameter(this.regularizedConstant, recordCount);
            LOG.debug("DEBUG: Weights: {}", Arrays.toString(this.weights));
            double finalTrainError = trainError / recordCount + reg;
            double finalTestError = testError / recordCount + reg;
            LOG.info("Iteration {} with train error {}, test error {}", context.getCurrentIteration(), finalTrainError,
                    finalTestError);
            return new LogisticRegressionParams(weights, finalTrainError, finalTestError, recordCount);
        }
    }

    private double regularizedParameter(double regularizedRate, long recordCount) {
        if(regularizedRate == 0.0d) {
            return 0.0d;
        }
        double sumSquareWeights = 0.0d;
        for(int i = 0; i < this.weights.length; i++) {
            sumSquareWeights += this.weights[i] * this.weights[i];
        }
        LOG.info("regularized_formula_master:" + regularizedRate + "*" + sumSquareWeights + "/" + recordCount + "*0.5");
        return regularizedRate * sumSquareWeights / recordCount * 0.5d;
    }

    private void loadConfigFiles(final Properties props) {
        try {
            SourceType sourceType = SourceType.valueOf(props.getProperty(NNConstants.NN_MODELSET_SOURCE_TYPE,
                    SourceType.HDFS.toString()));
            this.modelConfig = CommonUtils.loadModelConfig(props.getProperty(NNConstants.SHIFU_NN_MODEL_CONFIG),
                    sourceType);
            this.columnConfigList = CommonUtils.loadColumnConfigList(
                    props.getProperty(NNConstants.SHIFU_NN_COLUMN_CONFIG), sourceType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
