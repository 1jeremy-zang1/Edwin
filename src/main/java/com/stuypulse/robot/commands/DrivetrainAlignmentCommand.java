package com.stuypulse.robot.commands;

import com.stuypulse.robot.Constants.Alignment;
import com.stuypulse.robot.subsystems.Drivetrain;
import com.stuypulse.stuylib.control.Controller;
import com.stuypulse.stuylib.control.PIDController;
import com.stuypulse.stuylib.math.Angle;
import com.stuypulse.stuylib.math.SLMath;
import com.stuypulse.stuylib.network.limelight.Limelight;
import com.stuypulse.stuylib.streams.filters.IStreamFilterGroup;
import com.stuypulse.stuylib.streams.filters.LowPassFilter;
import com.stuypulse.stuylib.util.StopWatch;

/**
 * Drivetrain Alignment Command takes in a drivetrain, an aligner, and two
 * controllers. This lets you align the robot with whatever controllers you
 * want. Most commonly, a DrivetrainPIDAlignmentCommand is used instead as it
 * automatically provides the controllers for you.
 */
public class DrivetrainAlignmentCommand extends DrivetrainCommand {

    /**
     * This interface allows you to create classes that instruct the drivetrain to
     * move based off of error for speed and angle. If you use an aligner to define
     * your class, you can do things like auto tune.
     */
    public interface Aligner {
        // Called when command initialize is called,
        // Useful for relative encoder commands
        public default void init() { }

        // The amount of positional error
        public default double getSpeedError() { return 0.0; };

        // The amount of angular error
        public default Angle getAngleError() { return Angle.degrees(0); };
    }

    // Max speed for the robot
    private double maxSpeed;

    // Controllers for Alignment
    protected Controller speed;
    protected Controller angle;

    // Distance that the command will try to align with
    protected Aligner aligner;

    // Use encoder values with alignment command
    private boolean useInterpolation;
    private double targetDistance;
    private Angle targetAngle;

    // Used to check timeout of alignment
    private StopWatch pollingTimer;
    private StopWatch timer;

    // Misc Settings
    private boolean continuous; // Removes check for velocity
    private boolean neverFinish; // Waits to be interrupted
    private boolean minTime; // Waits to be interrupted

    /**
     * This creates a command that aligns the robot
     * 
     * @param drivetrain Drivetrain used by command to move
     * @param distance   target distance for robot to drive to
     * @param speed      controller used to align distance
     * @param angle      controller used to align the angle
     */
    public DrivetrainAlignmentCommand(Drivetrain drivetrain, Aligner aligner, Controller speed, Controller angle) {
        // Pass Drivetrain to the super class
        super(drivetrain);

        // Max speed
        this.maxSpeed = 1.0;

        // Initialize PID Controller for Speed
        this.speed = speed;

        // Initialize PID Controller for Angle
        this.angle = angle;

        // Target distance for the Alignment Command
        this.aligner = aligner;

        // Timer used to check when to update the errors
        this.useInterpolation = false;
        this.pollingTimer = new StopWatch();
        this.targetAngle = Angle.degrees(0);
        this.targetDistance = 0;

        // Used to check the alignment time.
        this.timer = new StopWatch();

        // Normally end the command once aligned
        this.neverFinish = false;
        this.continuous = false;
        this.minTime = false;
    }

    /**
     * This creates a command that aligns the robot
     * 
     * @param drivetrain Drivetrain used by command to move
     * @param distance   target distance for robot to drive to
     */
    public DrivetrainAlignmentCommand(Drivetrain drivetrain, Aligner aligner) {
        this(drivetrain, aligner, Alignment.Speed.getPID(), Alignment.Angle.getPID());
    }

    // Set the speed of the movement command
    public DrivetrainAlignmentCommand setMaxSpeed(double speed) {
        this.maxSpeed = speed;
        return this;
    }
    
    // Make the command never finish
    public DrivetrainAlignmentCommand setNeverFinish() {
        this.neverFinish = true;
        return this;
    }

    // Make command not check for velocity when finishing
    public DrivetrainAlignmentCommand setContinuous() {
        this.continuous = true;
        return this;
    }

    // Make command not check for velocity when finishing
    public DrivetrainAlignmentCommand useMinTime() {
        this.minTime = true;
        return this;
    }

    // Uses encoders to interpolate alignmnet data
    public DrivetrainAlignmentCommand useInterpolation() {
        this.useInterpolation = true;
        return this;
    }

    // Set the gear and other things when initializing
    public void initialize() {
        aligner.init();
        timer.reset();

        this.speed.setErrorFilter(new LowPassFilter(Alignment.Speed.IN_SMOOTH_FILTER.doubleValue()));
        this.speed.setOutputFilter(new IStreamFilterGroup(
            (x) -> SLMath.limit(x, maxSpeed),
            new LowPassFilter(Alignment.Speed.OUT_SMOOTH_FILTER.doubleValue())
        ));

        this.angle.setErrorFilter(new LowPassFilter(Alignment.Angle.IN_SMOOTH_FILTER.doubleValue()));
        this.angle.setOutputFilter(new IStreamFilterGroup(
            new LowPassFilter(Alignment.Angle.OUT_SMOOTH_FILTER.doubleValue())
        ));

        updateTargets();
        pollingTimer.reset();
    }

    // Update the targets with new alignment data
    public void updateTargets() {
        targetDistance = drivetrain.getDistance() + aligner.getSpeedError();
        targetAngle = drivetrain.getGyroAngle().add(aligner.getAngleError());
    }

    // Get distance left to travel
    public double getSpeedError() {
        if(this.useInterpolation) {
            return targetDistance - drivetrain.getDistance();
        } else {
            return aligner.getSpeedError();
        }
    }

    // Get angle left to turn
    public Angle getAngleError() {
        if(this.useInterpolation) {
            return targetAngle.sub(drivetrain.getGyroAngle());
        } else {
            return aligner.getAngleError();
        }
    }

    // Speed robot should move
    public double getSpeed() {
        // The more unaligned the robot is, the less it moves
        double s = 1.5 - Math.abs(angle.getError()) / Alignment.Angle.MAX_ANGLE_ERROR;
        return speed.update(this.getSpeedError()) * SLMath.limit(s, 0, 1.0);
    }

    // Angle robot has to turn
    public double getAngle() {
        return angle.update(getAngleError().toDegrees());
    }

    // Alignment must use low gear
    public Drivetrain.Gear getGear() {
        return Drivetrain.Gear.LOW;
    }

    // Aligning doesn't need to use curvature drive
    // Arcade drive is better for non humans
    public boolean useCurvatureDrive() {
        return false;
    }

    // Execute loop while also updating PID controllers
    public void execute() {
        super.execute();

        // Update PID controllers with new values   
        if(angle instanceof PIDController) {
            ((PIDController)angle).setP(Alignment.Angle.P.doubleValue());
            ((PIDController)angle).setI(Alignment.Angle.I.doubleValue());
            ((PIDController)angle).setD(Alignment.Angle.D.doubleValue());
        }

        if(speed instanceof PIDController) {
            ((PIDController)speed).setP(Alignment.Speed.P.doubleValue());
            ((PIDController)speed).setI(Alignment.Speed.I.doubleValue());
            ((PIDController)speed).setD(Alignment.Speed.D.doubleValue());
        }

        // Update targets if time has come
        if(pollingTimer.getTime() > Alignment.INTERPOLATION_PERIOD) {
            updateTargets();
            pollingTimer.reset();
        }
    }

    // Command is finished if all of the errors are small enough
    public boolean isFinished() {
        // If you do not want the command to automatically finish
        if(neverFinish) {
            return false;
        }

        // Check if the aligner hasn't run for long enough
        if(this.minTime && timer.getTime() < Alignment.MIN_ALIGNMENT_TIME) {
            return false;
        }

        // If continuous, do not check for velocity
        if(this.continuous) {
            return (speed.isDone(Alignment.Speed.MAX_SPEED_ERROR * 2.5) 
                 && angle.isDone(Alignment.Angle.MAX_ANGLE_ERROR * 1.5));
        } else {
            return (speed.isDone(Alignment.Speed.MAX_SPEED_ERROR, Alignment.Speed.MAX_SPEED_VEL) 
                 && angle.isDone(Alignment.Angle.MAX_ANGLE_ERROR, Alignment.Angle.MAX_ANGLE_VEL));
        }
    }

    // Turn limelight off when no longer aligning due to rules
    public void end(boolean interrupted) {
        Limelight.setLEDMode(Limelight.LEDMode.FORCE_OFF);
    }
}