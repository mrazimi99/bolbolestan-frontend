package org.ie.bolbolestan.system;

import org.ie.bolbolestan.entity.*;
import org.ie.bolbolestan.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Bolbolestan {
	private final Map<Integer, Course> courses;
	private final Map<Integer, Student> students;
	private final ObjectMapper objectMapper;

	public Bolbolestan() {
		this.courses = new HashMap<>();
		this.students = new HashMap<>();
		this.objectMapper = new ObjectMapper();
	}

	public ObjectNode execute(String command, String inputData) {
		ObjectNode message = this.objectMapper.createObjectNode();

		try {
			message.put("success", true);
			JsonNode jsonData = objectMapper.readTree(inputData);

			// Call the command handler.
			JsonNode outputData = switch (command) {
				case "addOffering" -> this.addOffering(jsonData);
				case "addStudent" -> this.addStudent(jsonData);
				case "getOfferings" -> this.getOfferings(jsonData);
				case "getOffering" -> this.getOffering(jsonData);
				case "addToWeeklySchedule" -> this.addToWeeklySchedule(jsonData);
				case "removeFromWeeklySchedule" -> this.removeFromWeeklySchedule(jsonData);
				case "getWeeklySchedule" -> this.getWeeklySchedule(jsonData);
				case "finalize" -> this.finalize(jsonData);
				default -> throw new CommandNotFoundException();
			};

			message.set("data", outputData);
		}
		catch (Exception error) {
			message.put("success", false);
			message.put("error", error.getMessage());
		}

		return message;
	}

	private ObjectNode addOffering(JsonNode jsonInput) {
		JsonNode classTimeNode = jsonInput.with("classTime");
		String[] days = this.objectMapper.convertValue(classTimeNode.withArray("days"), String[].class);
		JsonNode examTimeNode = jsonInput.get("examTime");

		int code = jsonInput.get("code").asInt();
		String name = jsonInput.get("name").asText();
		String instructor = jsonInput.get("Instructor").asText();
		int units = jsonInput.get("units").asInt();
		ClassTime classTime = new ClassTime(days, classTimeNode.get("time").asText());
		ExamTime examTime = new ExamTime(
				LocalDateTime.parse(examTimeNode.get("start").asText(), DateTimeFormatter.ofPattern("yyyy-M-d'T'HH:mm:ss")),
				LocalDateTime.parse(examTimeNode.get("end").asText(), DateTimeFormatter.ofPattern("yyyy-M-d'T'HH:mm:ss")));

		int capacity = jsonInput.get("capacity").asInt();
		String[] prerequisites = this.objectMapper.convertValue(jsonInput.withArray("prerequisites"), String[].class);
		this.courses.put(code, new Course(code, name, instructor, units, classTime, examTime, capacity, prerequisites));

		return this.objectMapper.createObjectNode();
	}

	private ObjectNode addStudent(JsonNode jsonInput) {
		Student newStudent = new Student(jsonInput.get("studentId").asInt(), jsonInput.get("name").asText(),
				Year.of(jsonInput.get("enteredAt").asInt()));

		this.students.put(jsonInput.get("studentId").asInt(), newStudent);

		return this.objectMapper.createObjectNode();
	}

	private ArrayNode getOfferings(JsonNode jsonInput) throws Exception {
		if (!students.containsKey(jsonInput.get("StudentId").asInt()))
			throw new StudentNotFoundException();

		ArrayNode answerData = this.objectMapper.createArrayNode();
		List<Course> coursesList = Arrays.asList(courses.values().toArray(new Course[0]));
		coursesList.sort(Comparator.comparing(Course::getName));

		for (Course course : coursesList)
			answerData.add(course.getJsonSummary());

		return answerData;
	}

	private ObjectNode getOffering(JsonNode jsonInput) throws Exception {
		if (!students.containsKey(jsonInput.get("StudentId").asInt()))
			throw new StudentNotFoundException();

		Course course = courses.get(jsonInput.get("code").asInt());
		if (course == null)
			throw new OfferingNotFoundException();

		return course.getJsonFullInfo();
	}

	private ObjectNode addToWeeklySchedule(JsonNode jsonInput) throws Exception {
		Student student = students.get(jsonInput.get("StudentId").asInt());
		if (student == null)
			throw new StudentNotFoundException();

		Course course = courses.get(jsonInput.get("code").asInt());
		if (course == null)
			throw new OfferingNotFoundException();

		student.addCourse(course);
		return this.objectMapper.createObjectNode();
	}

	private ObjectNode removeFromWeeklySchedule(JsonNode jsonInput) throws Exception {
		Student student = students.get(jsonInput.get("StudentId").asInt());
		if (student == null)
			throw new StudentNotFoundException();

		Course course = courses.get(jsonInput.get("code").asInt());
		if (course == null)
			throw new OfferingNotFoundException();

		student.removeCourse(course);
		return this.objectMapper.createObjectNode();
	}

	private ObjectNode getWeeklySchedule(JsonNode jsonInput) throws Exception {
		Student student = students.get(jsonInput.get("StudentId").asInt());
		if (student == null)
			throw new StudentNotFoundException();

		ObjectNode answerData = this.objectMapper.createObjectNode();
		ArrayNode weeklySchedule = this.objectMapper.createArrayNode();

		Map<Integer, SelectedCourse> courses = student.getCourses();
		SelectedCourse[] coursesList = courses.values().toArray(new SelectedCourse[0]);

		for (SelectedCourse selectedCourse : coursesList) {
			ObjectNode courseData = selectedCourse.getCourse().getJsonFullInfo();
			courseData.put("status", selectedCourse.getState().toString());

			weeklySchedule.add(courseData);
		}

		answerData.set("weeklySchedule", weeklySchedule);
		return answerData;
	}

	private void checkFinalizing(Student student) throws MultiException {
		MultiException exception = new MultiException();

		Map<Integer, SelectedCourse> courses = student.getCourses();
		List<SelectedCourse> coursesList = Arrays.asList(courses.values().toArray(new SelectedCourse[0]));

		int selectedUnits = student.getSelectedUnits();
		if (selectedUnits < 12)
			exception.addMessage(new MinimumUnitsException());

		if (selectedUnits > 20)
			exception.addMessage(new MaximumUnitsException());

		for (int i = 0; i < coursesList.size(); i++) {
			if ((coursesList.get(i).getState() == CourseState.NON_FINALIZED) &&
					(coursesList.get(i).getCourse().getNumberOfStudents() >= coursesList.get(i).getCourse().getCapacity()))
				exception.addMessage( new CapacityException(coursesList.get(i).getCourse().getCode()));

			// Checking Conflicts.
			for (int j = 0; j < coursesList.size(); j++) {
				if (i != j) {
					// Check Class Time Conflict.
					if (coursesList.get(i).getCourse().getClassTime().overlaps(coursesList.get(j).getCourse().getClassTime()))
						exception.addMessage(new ClassTimeCollisionException(coursesList.get(i).getCourse().getCode(),
								coursesList.get(j).getCourse().getCode()));

					// Check Exam Time Conflict.
					if (coursesList.get(i).getCourse().getExamTime().overlaps(coursesList.get(j).getCourse().getExamTime()))
						exception.addMessage(new ExamTimeCollisionException(coursesList.get(i).getCourse().getCode(),
								coursesList.get(j).getCourse().getCode()));
				}
			}
		}

		if (exception.hasError())
			throw exception;
	}

	private ObjectNode finalize(JsonNode json) throws Exception {
		Student student = students.get(json.get("StudentId").asInt());
		if (student == null)
			throw new StudentNotFoundException();

		checkFinalizing(student);
		student.finalizeCourses();

		return this.objectMapper.createObjectNode();
	}
}
