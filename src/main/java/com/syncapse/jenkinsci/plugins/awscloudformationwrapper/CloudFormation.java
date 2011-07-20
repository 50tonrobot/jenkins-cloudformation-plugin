/**
 * 
 */
package com.syncapse.jenkinsci.plugins.awscloudformationwrapper;

import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationAsyncClient;
import com.amazonaws.services.cloudformation.model.CreateStackRequest;
import com.amazonaws.services.cloudformation.model.DeleteStackRequest;
import com.amazonaws.services.cloudformation.model.DescribeStackEventsRequest;
import com.amazonaws.services.cloudformation.model.DescribeStackEventsResult;
import com.amazonaws.services.cloudformation.model.DescribeStacksRequest;
import com.amazonaws.services.cloudformation.model.DescribeStacksResult;
import com.amazonaws.services.cloudformation.model.Output;
import com.amazonaws.services.cloudformation.model.Parameter;
import com.amazonaws.services.cloudformation.model.Stack;
import com.amazonaws.services.cloudformation.model.StackEvent;
import com.amazonaws.services.cloudformation.model.StackStatus;
import com.google.common.collect.Lists;

/**
 * @author erickdovale
 * 
 */
public class CloudFormation {

	private String stackName;
	private String recipe;
	private List<Parameter> parameters;
	private long timeout;
	private String awsAccessKey;
	private String awsSecretKey;
	private PrintStream logger;
	private AmazonCloudFormation amazonClient;
	private Stack stack;
	private long waitBetweenAttempts;

	public CloudFormation(PrintStream logger, String stackName,
			String cloudFormationRecipe, Map<String, String> parameters,
			long timeout, String awsAccessKey, String awsSecretKey) {

		this.stackName = stackName;
		this.recipe = cloudFormationRecipe;
		this.parameters = parameters(parameters);
		this.awsAccessKey = awsAccessKey;
		this.awsSecretKey = awsSecretKey;
		this.timeout = timeout;
		this.logger = logger;
		this.amazonClient = getAWSClient();
		
		this.waitBetweenAttempts = 10; // query every 10s
		
		
	}
	
	private List<Parameter> parameters(Map<String, String> parameters) {

		if (parameters == null || parameters.values().size() == 0) {
			return null;
		}

		List<Parameter> result = Lists.newArrayList();
		Parameter parameter = null;
		for (String name : parameters.keySet()) {
			parameter = new Parameter();
			parameter.setParameterKey(name);
			parameter.setParameterValue(parameters.get(name));
			result.add(parameter);
		}

		return result;
	}


	/**
	 * @return
	 */
	public boolean delete() {
		logger.println("Deleting Cloud Formation stack: " + stackName);
		
		DeleteStackRequest deleteStackRequest = new DeleteStackRequest();
		deleteStackRequest.withStackName(stackName);
		
		amazonClient.deleteStack(deleteStackRequest);
		waitForStackToBeDeleted();
		
		logger.println("Cloud Formation stack: " + stackName
				+ " deleted successfully");
		return true;
	}

	/**
	 * @return A Map containing all outputs or null if creating the stack fails.
	 * 
	 * @throws IOException
	 */
	public Map<String, String> create() throws IOException {

		logger.println("Creating Cloud Formation stack: " + stackName);
		
		CreateStackRequest request = createStackRequest();
		
		try {
			amazonClient.createStack(request);
			stack = waitForStackToBeCreated();
			
			StackStatus status = getStackStatus(stack.getStackStatus());
			
			Map<String, String> stackOutput = new HashMap<String, String>();
			if (isStackCreationSuccessful(status)){
				List<Output> outputs = stack.getOutputs();
				for (Output output : outputs){
					stackOutput.put(output.getOutputKey(), output.getOutputValue());
				}
				
				logger.println("Successfully created stack: " + stackName);
				
				return stackOutput;
			} else{
				logger.println("Failed to create stack: " + stackName + ". Reason: " + stack.getStackStatusReason());
				return null;
			}
		} catch (AmazonClientException e) {
			logger.println("Failed to create stack: " + stackName + ". Reason: " + e.getLocalizedMessage());
			return null;
		}

	}

	protected AmazonCloudFormation getAWSClient() {
		AWSCredentials credentials = new BasicAWSCredentials(this.awsAccessKey,
				this.awsSecretKey);
		AmazonCloudFormation amazonClient = new AmazonCloudFormationAsyncClient(
				credentials);
		return amazonClient;
	}
	
	private void waitForStackToBeDeleted() {
		boolean deleted = false;
		while (!deleted){
			stack = getStack(amazonClient.describeStacks());
			deleted = (stack == null);
			if (!deleted) sleep();
		}
	}

	private Stack waitForStackToBeCreated() {
		DescribeStacksRequest describeStacksRequest = new DescribeStacksRequest().withStackName(stackName);
		StackStatus status = StackStatus.CREATE_IN_PROGRESS;
		Stack stack = null;
		long startTime = System.currentTimeMillis();
		while ( isStackCreationInProgress(status) && !isTimeout(startTime)){
			stack = getStack(amazonClient.describeStacks(describeStacksRequest));
			status = getStackStatus(stack.getStackStatus());
			if (isStackCreationInProgress(status)) sleep();
		}
		
		printStackEvents();
		
		return stack;
	}

	private void printStackEvents() {
		DescribeStackEventsRequest r = new DescribeStackEventsRequest();
		r.withStackName(stackName);
		DescribeStackEventsResult describeStackEvents = amazonClient.describeStackEvents(r);
		
		for (StackEvent event : describeStackEvents.getStackEvents()) {
			logger.println(event.getEventId() + " - " + event.getResourceStatus() + " - " + event.getResourceStatusReason());
		}
		
	}

	private boolean isTimeout(long startTime) {
		return (System.currentTimeMillis() - startTime) > (timeout * 1000);
	}

	private Stack getStack(DescribeStacksResult result) {
		for (Stack aStack : result.getStacks())
			if (stackName.equals(aStack.getStackName())){
				return aStack;
			}
		
		return null;
		
	}

	private boolean isStackCreationSuccessful(StackStatus status) {
		return status == StackStatus.CREATE_COMPLETE;
	}

	private void sleep() {
		try {
			Thread.sleep(waitBetweenAttempts * 1000);
		} catch (InterruptedException e) {
			if (stack != null){
				logger.println("Received an interruption signal. There is a stack created or in the proces of creation. Check in your amazon account to ensure you are not charged for this.");
				logger.println("Stack details: " + stack);
			}
		}
	}

	private boolean isStackCreationInProgress(StackStatus status) {
		return status == StackStatus.CREATE_IN_PROGRESS;
	}

	private StackStatus getStackStatus(String status) {
		StackStatus result = StackStatus.fromValue(status);
		return result;
	}

	private CreateStackRequest createStackRequest() throws IOException {

		CreateStackRequest r = new CreateStackRequest();
		r.withStackName(stackName);
		r.withParameters(parameters);

		r.withTemplateBody(recipe);

		return r;
	}

}
